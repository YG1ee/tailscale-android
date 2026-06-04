// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SplitTunnelExportPayload(
    val version: Int,
    val mode: String,
    val packages: List<String>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val exitNode: String? = null,
)

fun SplitTunnelExportPayload.validate(): Result<SplitTunnelExportPayload> {
  if (version != 1) {
    return Result.failure(
        IllegalArgumentException("Unsupported split tunnel export version: $version"))
  }
  if (mode !in setOf("include", "exclude")) {
    return Result.failure(IllegalArgumentException("Invalid split tunnel export mode: $mode"))
  }
  val blankPackage = packages.firstOrNull { it.isBlank() }
  if (blankPackage != null) {
    return Result.failure(
        IllegalArgumentException("Split tunnel export contains blank package entry"))
  }
  return Result.success(this)
}
