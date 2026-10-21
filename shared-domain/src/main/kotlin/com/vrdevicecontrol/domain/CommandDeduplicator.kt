package com.vrdevicecontrol.domain

/**
 * Bounded journal of command outcomes keyed by message ID. A duplicate with the same semantic
 * fingerprint returns the cached result without re-executing; a reused ID with a different
 * fingerprint is a conflict.
 */
class CommandDeduplicator<R>(private val capacity: Int = 256) {

    sealed interface Lookup<out R> {
        data object New : Lookup<Nothing>
        data class Duplicate<R>(val result: R) : Lookup<R>
        data object Conflict : Lookup<Nothing>
    }

    private data class Entry<R>(val fingerprint: String, val result: R)

    private val entries = object : LinkedHashMap<String, Entry<R>>(capacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<R>>?) = size > capacity
    }

    @Synchronized
    fun lookup(messageId: String, fingerprint: String): Lookup<R> {
        val entry = entries[messageId] ?: return Lookup.New
        return if (entry.fingerprint == fingerprint) Lookup.Duplicate(entry.result) else Lookup.Conflict
    }

    @Synchronized
    fun record(messageId: String, fingerprint: String, result: R) {
        entries[messageId] = Entry(fingerprint, result)
    }

    @Synchronized
    fun clear() = entries.clear()
}
