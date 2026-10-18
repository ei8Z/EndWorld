package com.ei8z.endworld.domain.npc.engine

import com.ei8z.endworld.domain.npc.model.DepartureStatus
import com.ei8z.endworld.domain.npc.model.Schedule
import com.ei8z.endworld.domain.npc.model.TimeSlot

data class MoveDecision(val targetLocationId: String, val moved: Boolean)

/** 只负责"该不该按时间表移动"；位置的合法性（是否邻接）由生成时间表时保证。 */
class NpcMovementEngine {

    fun decide(
        schedule: Schedule,
        gameHour: Int,
        currentLocationId: String,
        injured: Boolean,
        departureStatus: DepartureStatus?
    ): MoveDecision {
        if (injured) return MoveDecision(currentLocationId, moved = false)
        if (departureStatus != null && departureStatus != DepartureStatus.PRESENT) {
            return MoveDecision(currentLocationId, moved = false)
        }
        val slot = TimeSlot.entries.firstOrNull { slot ->
            if (slot.startHour < slot.endHour) gameHour in slot.startHour until slot.endHour
            else gameHour >= slot.startHour || gameHour < slot.endHour
        } ?: return MoveDecision(currentLocationId, moved = false)
        val target = schedule.timeSlots[slot] ?: return MoveDecision(currentLocationId, moved = false)
        return MoveDecision(target, moved = target != currentLocationId)
    }
}
