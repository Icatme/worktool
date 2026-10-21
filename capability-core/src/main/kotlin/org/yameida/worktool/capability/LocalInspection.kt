package org.yameida.worktool.capability

/** In-memory local diagnostics, never wire results or a durable delivery guarantee. */
data class LocalCommandRecord(val recordId: Int, val operation: String, val sequence: Int,
                              val state: String, val code: String)

data class LocalSnapshot(val closed: Boolean, val policyAvailable: Boolean,
                         val paused: Boolean, val accessibility: Boolean,
                         val textAllowed: Boolean, val eventsAllowed: Boolean,
                         val selectionBound: Boolean, val pending: Int,
                         val deliveryFailures: Long, val records: List<LocalCommandRecord>)
