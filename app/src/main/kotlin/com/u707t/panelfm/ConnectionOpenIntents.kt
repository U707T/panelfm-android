package com.u707t.panelfm

/** Keeps slow connection attempts from publishing after a newer open/disconnect intent. */
internal class ConnectionOpenIntents {
    data class Ticket(val id: Long, val generation: Long, val epoch: Long)
    private val lock = Any()
    private val generations = HashMap<Long, Long>()
    private var epoch = 0L

    fun begin(id: Long): Ticket = synchronized(lock) {
        val next = (generations[id] ?: 0L) + 1
        generations[id] = next
        Ticket(id, next, epoch)
    }

    fun publish(ticket: Ticket, action: () -> Unit): Boolean = synchronized(lock) {
        if (ticket.epoch != epoch || generations[ticket.id] != ticket.generation) return@synchronized false
        action()
        true
    }

    fun <T> disconnect(id: Long, action: () -> T): T = synchronized(lock) {
        generations[id] = (generations[id] ?: 0L) + 1
        action()
    }

    fun <T> disconnectAll(action: () -> T): T = synchronized(lock) {
        epoch++
        action()
    }
}
