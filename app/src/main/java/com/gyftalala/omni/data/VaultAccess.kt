package com.gyftalala.omni.data

import kotlinx.coroutines.sync.Mutex

/** All foreground vault mutations and background backup snapshots share one process-wide lock. */
internal object VaultAccess { val mutex = Mutex() }
