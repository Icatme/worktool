package org.yameida.worktool.capabilities

/** Local controls only; no wire message or saved legacy configuration can grant these. */
object LocalCapabilities {
    @Volatile var textAllowed = false
        private set
    @Volatile var eventsAllowed = false
        private set
    fun configure(text: Boolean, events: Boolean) {
        textAllowed = text
        eventsAllowed = events
        current?.invalidateSelection()
    }
    @Volatile var current: AndroidCapabilities? = null
        internal set
}
