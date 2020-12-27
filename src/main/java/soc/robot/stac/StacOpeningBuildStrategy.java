/**
 * Java Settlers - An online multiplayer version of the game Settlers of Catan
 * This file Copyright (C) 2020 Jeremy D Monin <jeremy@nand.net>
 * Portions of this file Copyright (C) 2017-2018 Strategic Conversation (STAC Project) https://www.irit.fr/STAC/
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * The maintainer of this program can be reached at jsettlers@nand.net
 **/

package soc.robot.stac;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import soc.disableDebug.D;
import soc.game.SOCGame;
import soc.game.SOCPlayer;
import soc.game.SOCPlayingPiece;
import soc.game.SOCResourceConstants;
import soc.game.SOCSettlement;
import soc.robot.OpeningBuildStrategy;

/**
 * The {@link OpeningBuildStrategy} for {@link StacRobotBrain}.
 * In STAC v1 this code was part of {@link StacRobotDM}.
 * @since 2.4.50
 */
public class StacOpeningBuildStrategy extends OpeningBuildStrategy
{

    /**
     * The brain object providing access to various utilities (e.g. estimators) and fields (e.g. firstSettlements)
     */
    protected final StacRobotBrain brain;

    public StacOpeningBuildStrategy(SOCGame ga, SOCPlayer pl, StacRobotBrain br) {
        super(ga, pl, br);
        brain = br;
    }

    @Override
    public int planInitialSettlements() {
        firstSettlement = getNextInitialSettlements();
        return firstSettlement;
    }

    @Override
    public int planSecondSettlement() {
        secondSettlement = getNextInitialSettlements();
        return secondSettlement;
    }

    /**
     * @return the coordinate of the best next legal location to place the initial settlement
     */
    protected int getNextInitialSettlements() {
        List<SettlementNode> legalSettlements = brain.getLegalSettlements();
        rankSettlements(legalSettlements, true); // NB: Our first settlement, we have no other settlements, so considerCurrent is irrelevant
        return legalSettlements.get(0).getNode();
    }

    /**
     * Plan our initial road.
     * 1) Rank remaining nodes, based on unconditional value (add opponent modeling later).
     * 2) Based on how many people are left to go (note existing logic uses people before our next placement, which is horribly wrong), trim the
     *    n+1 best nodes (have a little uncertainty - maybe make geometric?), along with any nodes invalidated by placements there
     * 3) Determine which of the 6 possible neighbours are legal based on this criterion (ie which are worth aiming for)
     * 4) Rank any legal targets
     * 5) Aim for the best legal target
     *
     * TODO: What if there are zero legal targets?
     *   Consider making the "likelihood" of a node remaining probabilistic, score the nodes and multiply it by the probability it's there.
     */
    @Override
    public int planInitRoad() {
        final int settlementNode = ourPlayerData.getLastSettlementCoord();

        List<SettlementNode> legalSettlements = brain.getLegalSettlements();
        rankSettlements(legalSettlements, false);

        // Initialize a dummy player to simulate placement of remaining settlements
        SOCPlayer dummy = new SOCPlayer(ourPlayerData.getPlayerNumber(), brain.getMemory().getGame());
        List<Integer> legalSettlementNodes = new ArrayList<Integer>();
        for (SettlementNode n : legalSettlements) {
            legalSettlementNodes.add(Integer.valueOf(n.getNode()));
        }
        dummy.setPotentialAndLegalSettlements(legalSettlementNodes, false, null);

        // How many are left to place?
        int remainingSettlements = 8 - brain.getMemory().getBoard().getSettlements().size();
        int i=0;
        // Mark off the n-best.  If we see a settlement that has been previously marked as blocked, mark it as blocked anyway, but don't decrement the
        //  remaining settlements to allocate.  It will frequently be the case that similarly ranked settlements are neighbours, and we should assume a
        //  worst case scenario that both are blocked
        while (remainingSettlements>0 && i<legalSettlements.size()) {
            int n = legalSettlements.get(i).getNode();
            SOCSettlement thisNode = new SOCSettlement(ourPlayerData, n, null);
            if (dummy.isPotentialSettlement(n)) {
                dummy.updatePotentials(thisNode);
                remainingSettlements--;
            }
            else {
                // if it's not currently potential, mark it as used anyway - be safe!
                dummy.updatePotentials(thisNode);
            }
            i++;
        }
        //List<SettlementNode> likelyAvailSettlements = getLegalSettlements(dummy);

        // Intersect this with
        List<Integer> nodesTwoAway = brain.getMemory().getBoard().getAdjacentNodesToNode2Away(settlementNode);
        List<SettlementNode> likelyTargets = new ArrayList<SettlementNode>();
        List<SettlementNode> legalTargets = new ArrayList<SettlementNode>();
        for (Integer n : nodesTwoAway) {
            SettlementNode node = new SettlementNode(n, brain.getMemory().getBoard());
            if (dummy.isPotentialSettlement(node.getNode())) { //likelyAvailSettlements.contains(node)) {
                if (legalSettlements.contains(node)) {
                    likelyTargets.add(node);
                    legalTargets.add(node);
                }
                else {
                    D.ebugWARNING("Potential settlement is not legal: " + node.getNode());
                }
            }
            else if (legalSettlements.contains(node)) {
                legalTargets.add(node);
            }
        }
        SettlementNode target = null;
        if (likelyTargets.size() > 0) {
            // TODO: Consider multiple settlements we can get by going in a certain direction
            rankSettlements(likelyTargets, true);
            target = likelyTargets.get(0);
        }
        else if (legalTargets.size() > 0) {
            // If there are no targets which are likely to be available, head for the best one that is currently legal and cross your fingers
            rankSettlements(legalTargets, true);
            target = legalTargets.get(0);
        }
        else {
            // Unlikely to have anywhere close to go from here.  What to do?
            //  Consider further distances
            //  Consider longest road potential
            // For now, just take a wild guess - aim for the first element in nodesTwoAway
            target = new SettlementNode(nodesTwoAway.get(0), brain.getMemory().getBoard());
        }

        int roadEdge = brain.getMemory().getBoard().getAdjacentEdgeToNode2Away(settlementNode, target.getNode());

        plannedRoadDestinationNode = target.getNode();
        return roadEdge;
    }

    /**
     * Ranks a set of legal locations to build a settlement
     * TODO: Modify this to allow use for rankCities
     * @param nodes the nodes to rank
     * @param considerCurrent consider the already built settlement(s) or not
     */
    protected void rankSettlements(List<SettlementNode> nodes, boolean considerCurrent) {
        for (SettlementNode node : nodes) {
            node.setScore(getScore(node, considerCurrent));
        }
        Collections.sort(nodes);
    }

    /**
     * Compute a value of a potential settlement.  Optionally consider
     * interactions with existing production
     *
     * @param nodeNum
     * @param considerCurrent true if we want to compute the score for a set including our existing settlements plus this one
     * @return
     */
    protected double getScore(SettlementNode node, boolean considerCurrent) {
        List<SettlementNode> nodes = new ArrayList<SettlementNode>();
        nodes.add(node);
        if (considerCurrent) {
        	Vector settlementPieces = ourPlayerData.getSettlements();
        	List<SettlementNode> currentSettlements = new ArrayList<SettlementNode>();
        	for(Object piece : settlementPieces){
        		currentSettlements.add(new SettlementNode(((SOCPlayingPiece)piece).getCoordinates(), brain.getGame().getBoard()));
        	}
            nodes.addAll(currentSettlements);
        }
        return getScore(nodes);
    }

    protected double getScore(List<SettlementNode> nodes) {
        // Loop through all settlements, determine what hexes and numbers are covered, and how many times
        //  This allows us to discount doubled values (eg score(2*6) < score(6, 8)).
        //  These are undesirable due to increasing variance (though we may want to flip that if you already need a miracle),
        //    and due to the fact it leads to robber
        // figure out what hexes are next to it, what they produce
        // also get ports
        // TODO: This only changes when we build settlements/cities, so should only do this
        //  when we build - then we only need to consider the new one under consideration.
        int[] numPerRoll = new int[13];
        Map<Integer, Integer> numPerHex = new HashMap<Integer, Integer>();
        for (SettlementNode node : nodes) {
            // Note: if we are evaluating a node without a settlement, it's a potential settlement, so treat it as one here
            int m = Math.max(node.getIncome(), 1);
            for (Hex hex : node.getHexes()) {
                numPerRoll[hex.getRoll()] += m;
                Integer nph = numPerHex.get(Integer.valueOf(hex.getCoord()));
                if (nph==null) {
                    nph = Integer.valueOf(m);
                }
                else {
                    nph = Integer.valueOf(nph + m);
                }
                numPerHex.put(Integer.valueOf(hex.getCoord()), nph);
            }
        }

        // discount factors - to be experimented with.
        double doubleRollDiscount = 0.95;
        double doubleHexDiscount = 0.7; // Note that double hex is also a double roll by definition

        double[] resourcePer36 = new double[6]; // robber is zero
        double totalResPer36 =0;

        boolean[] port = new boolean[6];
        // Now loop through again and increment
        for (SettlementNode node : nodes) {
            // Note: if we are evaluating a node without a settlement, it's a potential settlement, so treat it as one here
            int m = Math.max(node.getIncome(), 1);
            for (Hex hex : node.getHexes()) {
                double mult = m;
                if (numPerRoll[hex.getRoll()] > 1) {
                    mult *= doubleRollDiscount;
                    if (numPerHex.get(hex.getCoord()).intValue() > 1) {
                        mult *= doubleHexDiscount;
                    }
                }
                resourcePer36[hex.getType()] += mult * hex.getRollsPer36();
                if (hex.getType()>0) {
                    totalResPer36 += mult * hex.getRollsPer36();
                }
            }

            if (node.getPortType() >= 0) {
                port[node.getPortType()] = true;
            }
        }

        // now calculate score...
        double score = 0;

        // Add basic scores
        //  TODO: add appropriate valuation multipliers here based on rarity?
        score += resourcePer36[SOCResourceConstants.CLAY];
        score += resourcePer36[SOCResourceConstants.WOOD];
        score += resourcePer36[SOCResourceConstants.WHEAT];
        score += resourcePer36[SOCResourceConstants.SHEEP];
        score += resourcePer36[SOCResourceConstants.ORE];

        //  The number of resources of a given type we can expect to get through bank/port trades
        double[] portRes = new double[6];
        double[] portOrRollPer36 = new double[6];
        for (int i=1; i<6; i++) {
            for (int j=1; j<6; j++) {
                if (j!=i) {
                    // Determine the exchange rate based on whether ports are owned
                    if (port[j]) {
                        portRes[i] += resourcePer36[j] / 2.0;
                    }
                    else if (port[0]) {
                        portRes[i] += resourcePer36[j] / 3.0;
                    }
                    else {
                        portRes[i] += resourcePer36[j] / 4.0;
                    }
                }
            }
            // TODO: Add a discount factor for acquiring resources through ports?
            portOrRollPer36[i] = resourcePer36[i] + portRes[i];
        }

        // complementary combinations with arbitrary multipliers, which seem
        //  to work reasonably well in practice

        // ROAD
        double favour_roads_factor = 0.5;
        if (brain.isRobotType(StacRobotType.FAVOUR_ROADS_INITIALLY)) {
            favour_roads_factor = 0.7;
        }
        score += 0.5 * Math.pow(portOrRollPer36[SOCResourceConstants.CLAY]
                * portOrRollPer36[SOCResourceConstants.WOOD]
                        , favour_roads_factor);

        // SETTLMENT
        double favour_settlements_factor = 0.25;
        if (brain.isRobotType(StacRobotType.FAVOUR_SETTLEMENTS_INITIALLY)) {
            favour_settlements_factor = 0.5;
        }
        score += Math.pow(portOrRollPer36[SOCResourceConstants.CLAY]
                * portOrRollPer36[SOCResourceConstants.WOOD]
                        * portOrRollPer36[SOCResourceConstants.WHEAT]
                                * portOrRollPer36[SOCResourceConstants.SHEEP]
                                        , favour_settlements_factor);

        // CITY
        double favour_cities_factor = 0.2;
        if (brain.isRobotType(StacRobotType.FAVOUR_CITIES_INITIALLY)) {
            favour_cities_factor = (Double)brain.getTypeParam(StacRobotType.FAVOUR_CITIES_INITIALLY);
        }
        score += Math.pow(Math.pow(portOrRollPer36[SOCResourceConstants.ORE], 3)
                * Math.pow(portOrRollPer36[SOCResourceConstants.WHEAT], 2)
                , favour_cities_factor);

        // DEV CARD
        double favour_dev_cards_factor = 0.3333;
        if (brain.isRobotType(StacRobotType.FAVOUR_DEV_CARDS_INITIALLY)) {
            favour_dev_cards_factor = (Double)brain.getTypeParam(StacRobotType.FAVOUR_DEV_CARDS_INITIALLY);
        }
        score += 0.25 * Math.pow(portOrRollPer36[SOCResourceConstants.ORE]
                * portOrRollPer36[SOCResourceConstants.SHEEP]
                        * portOrRollPer36[SOCResourceConstants.WHEAT]
                                , favour_dev_cards_factor);

        // TODO: Bonus for nearby settlements to extend to (should be wary when we're in initial placement phase, likely to get blocked)
        return score;
    }

}
