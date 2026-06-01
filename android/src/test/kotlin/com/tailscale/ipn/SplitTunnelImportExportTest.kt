// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import com.tailscale.ipn.ui.model.SplitTunnelExportPayload
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.model.validate
import com.tailscale.ipn.ui.util.filterToInstalled
import com.tailscale.ipn.ui.viewModel.ExitNodeSkipReason
import com.tailscale.ipn.ui.util.classifyExitNode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

  @Test
  fun exportIncludesExitNode() {
    val payload =
        SplitTunnelExportPayload(
            version = 1,
            mode = "include",
            packages = listOf("com.a"),
            exitNode = "node-abc",
        )
    val jsonStr = Json.encodeToString(payload)
    val decoded = Json.decodeFromString<SplitTunnelExportPayload>(jsonStr)
    assertTrue("exitNode key must be present in JSON", jsonStr.contains("\"exitNode\""))
    assertEquals("node-abc", decoded.exitNode)
  }

  @Test
  fun exportOmitsExitNode() {
    val payload =
        SplitTunnelExportPayload(
            version = 1,
            mode = "include",
            packages = listOf("com.a"),
            exitNode = null,
        )
    val jsonStr = Json.encodeToString(payload)
    assertFalse("exitNode key must be absent when null", jsonStr.contains("\"exitNode\""))
  }

  @Test
  fun exportLegacyRoundTrip() {
    val original =
        SplitTunnelExportPayload(
            version = 1,
            mode = "exclude",
            packages = listOf("com.legacy.app"),
        )
    val jsonStr = Json.encodeToString(original)
    val decoded = Json.decodeFromString<SplitTunnelExportPayload>(jsonStr)
    val result = decoded.validate()
    assertTrue("Legacy payload without exitNode should validate", result.isSuccess)
    assertNull("exitNode should be null for legacy payload", decoded.exitNode)
  }

  @Test
  fun importExitNodeMissingIsNoOp() {
    val jsonStr = """{"version":1,"mode":"include","packages":["com.a"]}"""
    val payload = Json.decodeFromString<SplitTunnelExportPayload>(jsonStr)
    assertNull("Missing exitNode field should decode to null", payload.exitNode)
  }

  @Test
  fun importExitNodeNullIsNoOp() {
    val jsonStr = """{"version":1,"mode":"include","packages":["com.a"],"exitNode":null}"""
    val payload = Json.decodeFromString<SplitTunnelExportPayload>(jsonStr)
    assertNull(
        "exitNode null in JSON should be treated as no-op",
        payload.exitNode?.takeIf { it.isNotBlank() })
  }

  @Test
  fun importExitNodeBlankIsNoOp() {
    val jsonStr = """{"version":1,"mode":"include","packages":["com.a"],"exitNode":""}"""
    val payload = Json.decodeFromString<SplitTunnelExportPayload>(jsonStr)
    assertNull(
        "Blank exitNode should be treated as no-op",
        payload.exitNode?.takeIf { it.isNotBlank() })
  }

  @Test
  fun importExitNodePresentIsNonNull() {
    val jsonStr =
        """{"version":1,"mode":"include","packages":["com.a"],"exitNode":"node-xyz"}"""
    val payload = Json.decodeFromString<SplitTunnelExportPayload>(jsonStr)
    assertEquals("node-xyz", payload.exitNode?.takeIf { it.isNotBlank() })
  }

  @Test
  fun classifyExitNodeNotFound() {
    val result = classifyExitNode("missing-id", emptyList())
    assertEquals(ExitNodeSkipReason.NOT_FOUND, result)
  }

  @Test
  fun classifyExitNodeUnavailable() {
    val stub = Tailcfg.Node(StableID = "node-1")
    assertEquals(false, stub.isExitNode)
    val result = classifyExitNode("node-1", listOf(stub))
    assertEquals(ExitNodeSkipReason.UNAVAILABLE, result)
  }

  @Test
  fun classifyExitNodeApplicable() {
    val stub = Tailcfg.Node(StableID = "node-2", AllowedIPs = listOf("0.0.0.0/0", "::/0"))
    assertEquals(true, stub.isExitNode)
    val result = classifyExitNode("node-2", listOf(stub))
    assertNull("Exit node should be applicable (null skip reason)", result)
  }
}
