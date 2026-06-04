// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.viewModel.ExitNodeSkipReason

fun classifyExitNode(stableId: String, peers: List<Tailcfg.Node>): ExitNodeSkipReason? {
  val peer = peers.firstOrNull { it.StableID == stableId } ?: return ExitNodeSkipReason.NOT_FOUND
  return if (peer.isExitNode) null else ExitNodeSkipReason.UNAVAILABLE
}
