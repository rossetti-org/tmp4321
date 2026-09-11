/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package ksl.modeling.guidedpath

/**
 * Something that can hold a zone exclusively.
 *
 * A zone is the atom of contended space, and until now the only thing that could take one was a
 * [GuidedTransporter]. That is a restriction of the type and not of the idea: a work crew closing
 * an aisle, a pedestrian crossing, a door that is shut for a while -- each of these denies a zone
 * to traffic in exactly the way a transporter parked there does, and none of them is a vehicle.
 *
 * The interface asks for only the two things the subsystem genuinely needs from a holder, and it is
 * worth saying why each is the minimum rather than a convenience.
 *
 * [name] is needed because a holder is named in every message the subsystem can raise -- invariant
 * violations, deadlock reports, and the refusal to enter a held zone all say who is in the way.
 * Those messages are how a modeller finds a fault, and one that named an object rather than a
 * holder would be no help.
 *
 * [awaitedZone] is needed because it is the outgoing edge of the wait-for graph, and so is the only
 * thing deadlock detection asks of a holder. A holder that waits for nothing has no outgoing edge,
 * cannot close a cycle, and is therefore a terminal node of the walk: whatever is stuck behind it
 * is obstructed rather than deadlocked. That distinction is the whole reason the graph is walked,
 * and it falls out of this one property. It is also why *most* holders will answer null: a crossing
 * or a closed aisle occupies space without ever queuing for more, and the vehicle -- which does
 * queue -- is the special case, not the general one.
 *
 * Nothing here says how a holder takes a zone or gives it up. Those are the engine's business, and
 * keeping them off this interface is what stops anything outside the package from claiming space.
 */
interface ZoneHolderIfc {

    /** Unique enough to identify the holder in a message, and stable for the run. */
    val name: String

    /**
     * The zone this holder is waiting for, or null when it waits for nothing.
     *
     * Null is the ordinary answer. Only a holder that queues for space it does not yet have -- a
     * vehicle, in practice -- has anything else to report.
     */
    val awaitedZone: Zone?
}
