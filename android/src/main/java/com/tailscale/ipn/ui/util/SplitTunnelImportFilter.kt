// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

fun filterToInstalled(packages: List<String>, installedPackages: Set<String>): List<String> {
  return packages.filter { it in installedPackages }
}
