package ksl.modeling.agv.policies

import ksl.modeling.agv.AgvVehicle
import ksl.modeling.agv.Dispatcher

/**
 * What a vehicle offers when a dispatcher calls for proposals.
 *
 * Declared now and consulted from the negotiated-dispatching phase. It is here rather than there
 * because a vehicle carrying its own bidding rule -- rather than the dispatcher computing on the
 * vehicle's behalf -- is the substantive difference between an auction and a centrally-solved
 * assignment, and putting the field on the vehicle from the start keeps that honest.
 *
 * @return the vehicle's offer, lower being better, or null to decline the call
 */
fun interface BidPolicyIfc {
    fun bid(vehicle: AgvVehicle, task: Dispatcher.Task): Double?
}

/**
 * Bid the network distance to the pickup, and decline when it is unreachable.
 *
 * Distance along the guide path, never straight-line separation: on a one-way loop a vehicle
 * standing a few feet past a pickup point has to go all the way round, and bidding its proximity
 * would win it a job it is furthest from.
 */
class NetworkDistanceBid : BidPolicyIfc {

    override fun bid(vehicle: AgvVehicle, task: Dispatcher.Task): Double? {
        val network = vehicle.system.network
        val here = network.location(vehicle.currentLocationName) ?: return null
        val there = network.location(task.pickupLocation) ?: return null
        if (!network.isReachable(here, there)) return null
        return network.distance(here, there)
    }

    override fun toString(): String = "NetworkDistanceBid"
}
