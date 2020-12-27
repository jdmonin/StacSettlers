/**
 * Java Settlers - An online multiplayer version of the game Settlers of Catan
 * This file copyright (C) 2020 Jeremy D Monin <jeremy@nand.net>
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

import java.util.Random;

import soc.game.SOCBoard;
import soc.game.SOCGame;
import soc.game.SOCPlayer;
import soc.game.SOCResourceConstants;
import soc.game.SOCResourceSet;
import soc.robot.RobberStrategy;
import soc.robot.SOCBuildingSpeedEstimate;

/**
 * The {@link RobberStrategy} for {@link StacRobotBrain}.
 * In StacSettlers v1 this code was part of {@link StacRobotDM}.
 * @since 2.4.50
 */
public class StacRobberStrategy extends RobberStrategy
{

    public StacRobberStrategy(SOCGame ga, SOCPlayer pl, StacRobotBrain br, Random rand) {
        super(ga, pl, br, rand);
    }

    /**
     * {@inheritDoc}
     *<P>
     * In StacSettlers v1, this method was {@code StacRobotDM.selectMoveRobber(..)}.
     */
    @Override
    public int getBestRobberHex() {
        if (((StacRobotBrain) brain).isRobotType(StacRobotType.ROB_FOR_NEED)) {
            final int robberHex = game.getBoard().getRobberHex();
            int victim = selectPlayerToRobForNeed(robberHex);
            return super.selectRobberHex(robberHex, victim);
        } else {
            return super.getBestRobberHex();
        }
    }

    /**
     * Select a robbing victim based on where we are most likely to acquire our most needed resource
     * @param robberHex the current robber location
     * @return
     */
    protected int selectPlayerToRobForNeed(int robberHex) {
        // Determine what our most needed resource is - for now, just pick the one we get least of
        SOCBuildingSpeedEstimate estimate = brain.getEstimator(ourPlayerData.getNumbers());

        int[] rollsPerRes = estimate.getRollsPerResource();
        int mostNeededRes=0, mostNeededRolls = 0;

        for (int i=SOCResourceConstants.CLAY; i<SOCResourceConstants.UNKNOWN; i++) {
            if (rollsPerRes[i]>mostNeededRolls) {
                mostNeededRes = i;
                mostNeededRolls = rollsPerRes[i];
            }
        }

        // Where are we most likely to get it from?
        int bestVictim = -1;
        double bestProb = 0;
        for (int i=0; i<4; i++) {
            if (i!=ourPlayerData.getPlayerNumber()) {
                SOCResourceSet rs = ((StacRobotBrain) brain).getMemory().getOpponentResources(i);
                int amt = rs.getAmount(mostNeededRes);
                int total = rs.getTotal();
                double prob = (double) amt / (double) total;
                if (prob > bestProb) {
                    bestVictim = i;
                    bestProb = prob;
                }
            }
        }

        // if nobody has the resource we're looking for, default to thwart-best behaviour
        if (bestVictim<0) {
            bestVictim = super.selectPlayerToThwart(robberHex);
        }

        return bestVictim;
    }

}
