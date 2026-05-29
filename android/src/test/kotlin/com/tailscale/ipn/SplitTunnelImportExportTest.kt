// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import com.tailscale.ipn.ui.model.SplitTunnelExportPayload
import com.tailscale.ipn.ui.model.validate
import com.tailscale.ipn.ui.util.filterToInstalled
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelImportExportTest {

  @Test
  fun validateRoundTripPayload() {
    val original =
        SplitTunnelExportPayload(
            version = 1,
            mode = "include",
            packages = listOf("com.example.a", "com.example.b"),
        )
    val json = Json.encodeToString(original)
    val decoded = Json.decodeFromString<SplitTunnelExportPayload>(json)
    val result = decoded.validate()
    assertTrue("Valid v1 payload should succeed", result.isSuccess)
    assertEquals("Round-trip payload should equal original", original, result.getOrNull())
  }

  @Test
  fun validateUnknownVersion() {
    val payload =
        SplitTunnelExportPayload(
            version = 2,
            mode = "include",
            packages = listOf("com.example.a"),
        )
    val result = payload.validate()
    assertTrue("Unknown version should fail", result.isFailure)
  }

  @Test
  fun validateInvalidMode() {
    val payload =
        SplitTunnelExportPayload(
            version = 1,
            mode = "unknown",
            packages = listOf("com.example.a"),
        )
    val result = payload.validate()
    assertTrue("Invalid mode should fail", result.isFailure)
  }

  @Test
  fun validateBlankPackageEntry() {
    val payload =
        SplitTunnelExportPayload(
            version = 1,
            mode = "exclude",
            packages = listOf("com.example.a", ""),
        )
    val result = payload.validate()
    assertTrue("Blank package entry should fail", result.isFailure)
  }

  @Test
  fun validateWhitespacePackageEntry() {
    val payload =
        SplitTunnelExportPayload(
            version = 1,
            mode = "exclude",
            packages = listOf("com.example.a", "   "),
        )
    val result = payload.validate()
    assertTrue("Whitespace package entry should fail", result.isFailure)
  }

  @Test
  fun filterToInstalledSubset() {
    val input = listOf("com.example.a", "com.example.missing")
    val installed = setOf("com.example.a")
    val result = filterToInstalled(input, installed)
    assertEquals(listOf("com.example.a"), result)
  }

  @Test
  fun filterToInstalledAllMissing() {
    val input = listOf("com.example.missing1", "com.example.missing2")
    val installed = emptySet<String>()
    val result = filterToInstalled(input, installed)
    assertTrue("All missing should return empty list", result.isEmpty())
  }

  @Test
  fun filterToInstalledAllPresent() {
    val input = listOf("com.example.a", "com.example.b")
    val installed = setOf("com.example.a", "com.example.b")
    val result = filterToInstalled(input, installed)
    assertEquals(input, result)
  }
}
