/**
 * Java Settlers - An online multiplayer version of the game Settlers of Catan
 * This file Copyright (C) 2020-2021 Jeremy D Monin <jeremy@nand.net>
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

import originalsmartsettlers.boardlayout.BoardLayout;
import originalsmartsettlers.boardlayout.GameStateConstants;
import originalsmartsettlers.player.Player;
import soc.disableDebug.D;
import soc.game.SOCGame;
import soc.game.SOCPlayer;

/**
 * The {@link OpeningBuildStrategy} for {@link OriginalSSRobotBrain}.
 * @since 2.4.50
 */
public class OriginalSSOpeningBuildStrategy extends StacOpeningBuildStrategy
{

    /**
     * used in planning where to put our first settlement
     */
    protected int firstSettlement = -1;

    /**
     * used in planning where to put our second settlement
     */
    protected int secondSettlement = -1;

    public OriginalSSOpeningBuildStrategy(SOCGame ga, SOCPlayer pl, OriginalSSRobotBrain br) {
        super(ga, pl, br);
    }

    /**
     * {@inheritDoc}
     *<P>
     * In StacSettlers v1, this code was part of {@link OriginalSSRobotBrain#placeFirstSettlement()}.
     */
    @Override
    public int planInitialSettlements() {
        final OriginalSSRobotBrain br = (OriginalSSRobotBrain) brain;
        final BoardLayout bl = br.bl;
        final int playerNumber = br.getPlayerNumber();

    	if(firstSettlement != -1){
    		System.err.println("Robot " + playerNumber + " asked to place first settlement twice");
    		return firstSettlement;
    	}

        br.sendStateToSmartSettlers(GameStateConstants.S_SETTLEMENT1);
        Player p = bl.player[playerNumber];
        bl.possibilities.Clear();
        p.listInitSettlementPossibilities(bl.state);
        p.selectAction(bl.state, bl.action);
        p.performAction(bl.state, bl.action);
        String s = String.format("Performing action: [%d %d %d %d %d]", bl.action[0], bl.action[1], bl.action[2], bl.action[3], bl.action[4]);
        D.ebugPrintlnINFO(s);
        firstSettlement = br.translateVertexToJSettlers(bl.action[1]);

        D.ebugPrintlnINFO("BUILD REQUEST FOR FIRST SETTLEMENT AT "+Integer.toHexString(firstSettlement));

        return firstSettlement;
    }

    /**
     * {@inheritDoc}
     *<P>
     * In StacSettlers v1, this code was part of {@link OriginalSSRobotBrain#placeSecondSettlement()}.
     */
    @Override
    public int planSecondSettlement() {
        final OriginalSSRobotBrain br = (OriginalSSRobotBrain) brain;
        final BoardLayout bl = br.bl;
        final int playerNumber = br.getPlayerNumber();

    	if(secondSettlement != -1){
    		System.err.println("Robot " + playerNumber + " asked to place second settlement twice");;
    		return secondSettlement;
    	}

        br.sendStateToSmartSettlers(GameStateConstants.S_SETTLEMENT2);
        Player p = bl.player[playerNumber];
        bl.possibilities.Clear();
        p.listInitSettlementPossibilities(bl.state);
        p.selectAction(bl.state, bl.action);
        p.performAction(bl.state, bl.action);
        String s = String.format("Performing action: [%d %d %d %d %d]", bl.action[0], bl.action[1], bl.action[2], bl.action[3], bl.action[4]);
        D.ebugPrintlnINFO(s);
        secondSettlement = br.translateVertexToJSettlers(bl.action[1]);

        D.ebugPrintlnINFO("BUILD REQUEST FOR SECOND SETTLEMENT AT "+Integer.toHexString(secondSettlement));
        return secondSettlement;
    }

    /**
     * {@inheritDoc}
     *<P>
     * In StacSettlers v1, this code was part of {@link OriginalSSRobotBrain#placeInitRoad()}.
     */
    @Override
    public int planInitRoad() {
        final OriginalSSRobotBrain br = (OriginalSSRobotBrain) brain;
        final BoardLayout bl = br.bl;

        br.sendStateToSmartSettlers(GameStateConstants.S_ROAD1); // does not matter if ROAD1 or ROAD2
        Player p = bl.player[game.getCurrentPlayerNumber()];
        bl.possibilities.Clear();
        p.listInitRoadPossibilities(bl.state);
        p.selectAction(bl.state, bl.action);
        p.performAction(bl.state, bl.action);

        String s = String.format("Performing action: [%d %d %d %d %d]", bl.action[0], bl.action[1], bl.action[2], bl.action[3], bl.action[4]);
        D.ebugPrintlnINFO(s);
        int roadEdge = br.translateEdgeToJSettlers(bl.action[1]);
        plannedRoadDestinationNode = -1;  // we don't know where SmartSettlers planned to go next

        D.ebugPrintlnINFO("!!! PUTTING INIT ROAD !!!");
        D.ebugPrintlnINFO("Trying to build first road at "+Integer.toHexString(roadEdge));
        return roadEdge;

        //dummy.destroyPlayer();
    }

}
