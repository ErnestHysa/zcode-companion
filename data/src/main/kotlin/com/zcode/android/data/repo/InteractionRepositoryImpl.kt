package com.zcode.android.data.repo

import androidx.room.withTransaction
import com.zcode.android.data.db.InteractionEntity
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.InteractionId
import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.model.InteractionLifecycle
import com.zcode.android.domain.model.PermissionRequest
import com.zcode.android.domain.model.UserInputRequest
import com.zcode.android.domain.repo.InteractionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Interaction store (§17.3 row 3): open/recent permission + user-input interactions;
 * resolved > 24 h dropped; total bound ≤ 64. Card STATE machinery (§14.4.3) is
 * Phase 5.4 — this store persists lifecycle verbatim; the fail-closed option
 * semantics live in the domain model ([PermissionOption]), not here.
 */
class InteractionRepositoryImpl(
    private val db: ZcodeDatabase,
    private val bounds: Bounds = Bounds(),
    private val clock: () -> Long = System::currentTimeMillis,
) : InteractionRepository {

    data class Bounds(
        /** §17.3: resolved > 24 h dropped. */
        val resolvedTtlMs: Long = 24L * 60 * 60 * 1000,
        /** §17.3: ≤ 64 open/recent. */
        val maxTotal: Int = 64,
    )

    private val dao = db.interactionDao()
    private val json = Json

    override fun observeOpen(endpointId: EndpointId): Flow<List<InteractionRepository.InteractionRecord>> =
        dao.observeOpen(endpointId.raw).map { list -> list.map { it.toRecord() } }

    override suspend fun upsertPermission(request: PermissionRequest, endpointId: EndpointId, lifecycle: InteractionLifecycle) {
        dao.upsert(
            InteractionEntity(
                endpointId = endpointId.raw,
                interactionId = request.interactionId.raw,
                sessionId = request.sessionId,
                kind = Kind.PERMISSION,
                lifecycle = lifecycle.name,
                toolName = request.toolName,
                payloadJson = request.raw?.toString() ?: "{}",
                createdAtMs = request.createdAtMs,
                resolvedAtMs = request.resolvedAtMs,
            ),
        )
    }

    override suspend fun upsertUserInput(request: UserInputRequest, endpointId: EndpointId, lifecycle: InteractionLifecycle) {
        dao.upsert(
            InteractionEntity(
                endpointId = endpointId.raw,
                interactionId = request.interactionId.raw,
                sessionId = request.sessionId,
                kind = Kind.USER_INPUT,
                lifecycle = lifecycle.name,
                toolName = null,
                payloadJson = request.raw?.toString() ?: "{}",
                createdAtMs = request.createdAtMs,
                resolvedAtMs = request.resolvedAtMs,
            ),
        )
    }

    override suspend fun markResolved(endpointId: EndpointId, interactionId: InteractionId, atMs: Long, resolvedBySelf: Boolean) {
        dao.resolve(endpointId.raw, interactionId.raw, InteractionLifecycle.RESOLVED.name, atMs)
    }

    override suspend fun sweep(endpointId: EndpointId, nowMs: Long): Int {
        var dropped = 0
        val all = dao.listAll(endpointId.raw)
        // 1) resolved > TTL dropped (§17.3)
        val staleResolved = all.filter { it.resolvedAtMs != null && nowMs - it.resolvedAtMs > bounds.resolvedTtlMs }
        if (staleResolved.isNotEmpty()) {
            dao.deleteByIds(endpointId.raw, staleResolved.map { it.interactionId })
            dropped += staleResolved.size
        }
        // 2) ≤ 64 total: evict oldest RESOLVED first, then oldest overall (§17.3 bound).
        var remaining = dao.listAll(endpointId.raw)
        if (remaining.size > bounds.maxTotal) {
            val overBy = remaining.size - bounds.maxTotal
            val resolvedFirst = remaining.filter { it.resolvedAtMs != null }.sortedBy { it.createdAtMs }
            val victims = (resolvedFirst.take(overBy).map { it.interactionId }.toSet() +
                remaining.sortedBy { it.createdAtMs }.map { it.interactionId })
                .toList()
                .distinct()
                .take(overBy)
            dao.deleteByIds(endpointId.raw, victims)
            dropped += victims.size
        }
        return dropped
    }

    override suspend fun count(endpointId: EndpointId): Int = dao.count(endpointId.raw)

    override suspend fun loadStored(endpointId: EndpointId, interactionId: InteractionId): InteractionRepository.StoredInteraction? {
        val entity = dao.get(endpointId.raw, interactionId.raw) ?: return null
        val payload = runCatching { json.parseToJsonElement(entity.payloadJson).jsonObject }.getOrDefault(
            kotlinx.serialization.json.JsonObject(emptyMap()),
        )
        return InteractionRepository.StoredInteraction(entity.toRecord(), payload)
    }

    private fun InteractionEntity.toRecord() = InteractionRepository.InteractionRecord(
        interactionId = InteractionId(interactionId),
        sessionId = sessionId?.let { SessionId(it) },
        kind = when (kind) {
            Kind.PERMISSION -> InteractionRepository.InteractionRecord.Kind.PERMISSION
            else -> InteractionRepository.InteractionRecord.Kind.USER_INPUT
        },
        lifecycle = runCatching { InteractionLifecycle.valueOf(lifecycle) }.getOrDefault(InteractionLifecycle.FAILED),
        toolName = toolName,
        createdAtMs = createdAtMs,
        resolvedAtMs = resolvedAtMs,
        // L3: the retained payload reaches the inbox as ONE bounded, redacted line.
        preview = com.zcode.android.domain.model.InteractionPreview.of(
            toolName,
            runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrNull(),
        ),
    )

    private object Kind {
        const val PERMISSION = "PERMISSION"
        const val USER_INPUT = "USER_INPUT"
    }
}
