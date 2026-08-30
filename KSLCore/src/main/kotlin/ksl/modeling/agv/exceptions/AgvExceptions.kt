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
package ksl.modeling.agv.exceptions

/**
 * An assignment policy named a vehicle that had not declared itself available.
 *
 * Availability is asserted by a vehicle, never inferred by the dispatcher (invariant `A6`), so a
 * policy that returns a vehicle the dispatcher did not offer it has reached outside what it was
 * given. That is a policy defect rather than a modelling condition, which is why it raises instead
 * of being skipped: a silently dropped proposal would show up much later as a fleet that never
 * quite keeps up.
 */
class AgvDispatchException(message: String) : RuntimeException(message)

/**
 * An assignment was revoked after its load was aboard, or used after it had completed.
 *
 * Invariant `A4`: once a vehicle has taken possession, it finishes the delivery. Re-tasking in
 * flight is legitimate and supported, but only up to that instant.
 */
class AgvAssignmentException(message: String) : RuntimeException(message)

/**
 * A task was completed twice, or a suspended entity was resumed twice.
 *
 * Invariant `A9`: an entity that posted a transport task is resumed exactly once. A second resume
 * would run the load's process from a continuation that has already been consumed, and the symptom
 * appears far from the cause, so it is worth catching where it happens.
 */
class AgvProtocolException(message: String) : RuntimeException(message)
