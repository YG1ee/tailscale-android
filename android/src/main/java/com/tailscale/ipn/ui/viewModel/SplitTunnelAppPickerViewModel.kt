// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.SettingState
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.SplitTunnelExportPayload
import com.tailscale.ipn.ui.model.validate
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.InstalledApp
import com.tailscale.ipn.ui.util.InstalledAppsManager
import com.tailscale.ipn.ui.util.classifyExitNode
import com.tailscale.ipn.ui.util.filterToInstalled
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Result of an import operation. */
/** Describes why an imported exit-node could not be applied. */
enum class ExitNodeSkipReason { NOT_FOUND, UNAVAILABLE }

sealed class ImportResult {
  /** Import succeeded; [skippedCount] packages were not installed and were skipped. */
  data class Success(val skippedCount: Int) : ImportResult()

  /** Import partially succeeded; split-tunnel import worked, exit-node import was skipped. */
  data class PartialSuccess(val skippedCount: Int, val exitNodeSkipReason: ExitNodeSkipReason) : ImportResult()

  /** Export succeeded. */
  data object ExportSuccess : ImportResult()

  /** Import failed with a human-readable [message]. */
  data class Error(val message: String) : ImportResult()
}

private data class SplitTunnelApplyResult(val skippedCount: Int, val exitNodeId: String?)

class SplitTunnelAppPickerViewModel : ViewModel() {
  val installedAppsManager = InstalledAppsManager(packageManager = App.get().packageManager)

  val installedApps: StateFlow<List<InstalledApp>> =
      flow {
            emit(installedAppsManager.fetchInstalledApps())
            initSelectedPackageNames()
          }
          .flowOn(Dispatchers.IO)
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(5000),
              initialValue = listOf(),
          )
  val selectedPackageNames: StateFlow<List<String>> = MutableStateFlow(listOf())

  val allowSelected: StateFlow<Boolean> = MutableStateFlow(App.get().allowSelectedPackages())
  val showHeaderMenu: StateFlow<Boolean> = MutableStateFlow(false)
  val showSwitchDialog: StateFlow<Boolean> = MutableStateFlow(false)

  val mdmExcludedPackages: StateFlow<SettingState<String?>> = MDMSettings.excludedPackages.flow
  val mdmIncludedPackages: StateFlow<SettingState<String?>> = MDMSettings.includedPackages.flow

  private var saveJob: Job? = null

  private val _requestExport = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val requestExport: SharedFlow<Unit> = _requestExport.asSharedFlow()

  private val _requestImport = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val requestImport: SharedFlow<Unit> = _requestImport.asSharedFlow()

  private val _importResult = MutableSharedFlow<ImportResult>(extraBufferCapacity = 1)
  val importResult: SharedFlow<ImportResult> = _importResult.asSharedFlow()

  fun onExportRequested() {
    _requestExport.tryEmit(Unit)
  }

  fun onImportRequested() {
    _requestImport.tryEmit(Unit)
  }

  fun onImportFileSelected(uri: Uri) {
    saveJob?.cancel()
    viewModelScope.launch(Dispatchers.IO) {
      val app = App.get()
      val invalidFormatMessage = app.getString(R.string.split_tunnel_import_error_invalid)
      val unsupportedVersionMessage = app.getString(R.string.split_tunnel_import_error_version)
      val jsonString =
          try {
            app.contentResolver.openInputStream(uri)?.use {
              it.readBytes().toString(Charsets.UTF_8)
            }
                ?: run {
                  _importResult.tryEmit(ImportResult.Error(invalidFormatMessage))
                  return@launch
                }
          } catch (e: Exception) {
            _importResult.tryEmit(ImportResult.Error(invalidFormatMessage))
            return@launch
          }

      val payload =
          try {
            Json.decodeFromString<SplitTunnelExportPayload>(jsonString)
          } catch (e: SerializationException) {
            _importResult.tryEmit(ImportResult.Error(invalidFormatMessage))
            return@launch
          } catch (e: Exception) {
            _importResult.tryEmit(ImportResult.Error(invalidFormatMessage))
            return@launch
          }

      val validationResult = payload.validate()
      if (validationResult.isFailure) {
        val e = validationResult.exceptionOrNull()
        val message =
            if (e?.message?.contains("version", ignoreCase = true) == true) {
              unsupportedVersionMessage
            } else {
              invalidFormatMessage
            }
        _importResult.tryEmit(ImportResult.Error(message))
        return@launch
      }

      val result = applyImport(payload)
      val skippedCount = result.skippedCount
      val exitNodeId = result.exitNodeId

      if (exitNodeId == null) {
        _importResult.tryEmit(ImportResult.Success(skippedCount))
        return@launch
      }

      val peers = Notifier.netmap.value?.Peers ?: emptyList()
      val skipReason = classifyExitNode(exitNodeId, peers)
      if (skipReason != null) {
        _importResult.tryEmit(ImportResult.PartialSuccess(skippedCount, skipReason))
        return@launch
      }

      val prefsOut = Ipn.MaskedPrefs()
      prefsOut.ExitNodeID = exitNodeId
      Client(viewModelScope).editPrefs(prefsOut) { applyResult ->
        if (applyResult.isFailure) {
          _importResult.tryEmit(
              ImportResult.PartialSuccess(skippedCount, ExitNodeSkipReason.UNAVAILABLE))
        } else {
          _importResult.tryEmit(ImportResult.Success(skippedCount))
        }
      }
    }
  }

  private fun applyImport(payload: SplitTunnelExportPayload): SplitTunnelApplyResult {
    val installedPackages = installedAppsManager.fetchInstalledApps().map { it.packageName }.toSet()
    val filteredPackages = filterToInstalled(payload.packages, installedPackages)
    val skippedCount = payload.packages.size - filteredPackages.size
    val exitNodeId: String? = payload.exitNode?.takeIf { it.isNotBlank() }

    App.get().applyImportedPackages(payload.mode, filteredPackages)
    initSelectedPackageNames()
    return SplitTunnelApplyResult(skippedCount, exitNodeId)
  }

  fun onExportFileSelected(uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      val app = App.get()
      val exportFailedMessage = app.getString(R.string.split_tunnel_export_error)
      try {
        val mode = if (app.allowSelectedPackages()) "include" else "exclude"
        val packages = app.selectedPackageNames() - app.builtInDisallowedPackageNames.toSet()
        val exitNode = app.selectedExitNodeID()
        val payload = SplitTunnelExportPayload(version = 1, mode = mode, packages = packages, exitNode = exitNode)
        val json = Json.encodeToString(SplitTunnelExportPayload.serializer(), payload)
        app.contentResolver.openOutputStream(uri, "rwt")?.use { stream ->
          stream.write(json.toByteArray(Charsets.UTF_8))
        }
            ?: run {
              _importResult.emit(ImportResult.Error(exportFailedMessage))
              return@launch
            }
        _importResult.emit(ImportResult.ExportSuccess)
      } catch (e: Exception) {
        _importResult.emit(ImportResult.Error(e.message ?: exportFailedMessage))
      }
    }
  }

  private fun initSelectedPackageNames() {
    allowSelected.set(App.get().allowSelectedPackages())
    selectedPackageNames.set(
        App.get()
            .selectedPackageNames()
            .let {
              if (!allowSelected.value) {
                it.union(App.get().builtInDisallowedPackageNames)
              } else {
                it
              }
            }
            .intersect(installedApps.value.map { it.packageName }.toSet())
            .toList())
  }

  fun performSelectionSwitch() {
    App.get().switchUserSelectedPackages()
    initSelectedPackageNames()
  }

  fun select(packageName: String) {
    if (selectedPackageNames.value.contains(packageName)) return

    selectedPackageNames.set(selectedPackageNames.value + packageName)
    debounceSave()
  }

  fun deselect(packageName: String) {
    selectedPackageNames.set(selectedPackageNames.value - packageName)
    debounceSave()
  }

  private fun debounceSave() {
    saveJob?.cancel()
    saveJob =
        viewModelScope.launch {
          delay(500) // Wait to batch multiple rapid updates
          App.get().updateUserSelectedPackages(selectedPackageNames.value)
        }
  }
}
