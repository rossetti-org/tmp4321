package ksl.modeling.agv

/**
 * What one transport cost the load that asked for it.
 *
 * The three durations partition the wait: `waitForAssignment` runs from posting until a vehicle
 * committed, `waitForArrival` from there until the load was aboard, and `transportTime` from there
 * until it was set down. Their sum is `totalTime`.
 *
 * @param totalTime posting to delivery
 * @param waitForAssignment posting until a vehicle committed to the task
 * @param waitForArrival the commitment until the load was aboard
 * @param transportTime aboard until set down, including any unloading delay
 * @param blockedTime how much of the above the vehicle spent unable to claim the space ahead of it
 * @param routeLength how far the vehicle travelled while carrying the load
 * @param vehicleName which vehicle carried it
 * @param numReassignments how many times the task was taken back and given to someone else. No
 *   counterpart in the passive subsystem's result: it is the observable trace of re-tasking, and
 *   is zero unless a policy revokes.
 */
data class AgvTransportResult(
    val totalTime: Double,
    val waitForAssignment: Double,
    val waitForArrival: Double,
    val transportTime: Double,
    val blockedTime: Double,
    val routeLength: Double,
    val vehicleName: String,
    val numReassignments: Int
) {
    override fun toString(): String =
        "AgvTransportResult(total=$totalTime, waitForAssignment=$waitForAssignment, " +
                "waitForArrival=$waitForArrival, transport=$transportTime, blocked=$blockedTime, " +
                "distance=$routeLength, vehicle=$vehicleName, reassignments=$numReassignments)"
}
