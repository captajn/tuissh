package com.tuissh.app.ssh.proxy

import com.tuissh.app.core.NeoError
import com.tuissh.app.data.dao.ServerDao
import com.tuissh.app.data.model.ServerEntity

/**
 * Resolves a `ProxyJump` / bastion chain into an ordered hop list.
 *
 * The original app stored bastions as `bastionServerId` on each server and rejected
 * cycles with `Cyclic bastion configuration: <chain>`. This class produces the hop
 * order and refuses cycles before any socket is opened.
 */
class ProxyChainResolver(private val serverDao: ServerDao) {

    /** Ordered hops from the outermost bastion to the final target. */
    data class Chain(
        /** Bastions in connect order: index 0 is reached directly. */
        val hops: List<ServerEntity>,
        /** The final destination. */
        val target: ServerEntity,
    ) {
        val depth: Int get() = hops.size + 1
    }

    /**
     * Build the chain for [server].
     *
     * Returns [NeoError.CyclicBastion] when the bastion graph loops, and
     * [NeoError.BastionNotFound] when a referenced server id no longer exists.
     */
    suspend fun resolve(server: ServerEntity): Result {
        val hops = ArrayList<ServerEntity>()
        val visited = LinkedHashSet<Long>()
        var current: ServerEntity? = server
        var guard = 0

        while (current != null) {
            val node = current
            if (guard++ > MAX_DEPTH) {
                return Result.Failure(
                    NeoError.CyclicBastion(visited.joinToString(" → ") { "#$it" }),
                )
            }
            if (!visited.add(node.id)) {
                val chain = visited.joinToString(" → ") { id ->
                    if (id == node.id) node.name else "#$id"
                }
                return Result.Failure(NeoError.CyclicBastion(chain))
            }

            val bastionId = node.bastionServerId ?: break
            val bastion = serverDao.findById(bastionId)
                ?: return Result.Failure(NeoError.BastionNotFound("server #$bastionId"))

            hops.add(0, bastion)
            current = bastion
        }

        return Result.Success(Chain(hops = hops, target = server))
    }

    sealed interface Result {
        data class Success(val chain: Chain) : Result
        data class Failure(val error: NeoError) : Result
    }

    private companion object {
        /** Matches the guard the original app used; bastion chains never exceed this. */
        const val MAX_DEPTH = 16
    }
}
