/**
 * Java Settlers - An online multiplayer version of the game Settlers of Catan
 * This file copyright (C) 2020-2021 Jeremy D Monin <jeremy@nand.net>
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

import mcts.MCTS;
import mcts.game.Game;
import mcts.game.catan.GameStateConstants;
import mcts.listeners.SearchListener;
import mcts.utils.Timer;
import soc.debug.D;
import soc.game.SOCBoard;
import soc.game.SOCGame;
import soc.game.SOCPlayer;

/**
 * The {@link OpeningBuildStrategy} for {@link MCTSRobotBrain}.
 * @since 2.4.50
 */
public class MCTSOpeningBuildStrategy extends StacOpeningBuildStrategy
{

    /**
     * used in planning where to put our first settlement
     */
    private int firstSettlement = -1;
    /**
     * used in planning where to put our second settlement
     */
    private int secondSettlement = -1;

    private MCTS mcts;

    public MCTSOpeningBuildStrategy(SOCGame ga, SOCPlayer pl, MCTSRobotBrain br, MCTS mcts) {
        super(ga, pl, br);
        this.mcts = mcts;
    }

    @Override
    public int planInitialSettlements() {
        return planInitialSettlement(GameStateConstants.S_SETTLEMENT1);
    }

    @Override
    public int planSecondSettlement() {
        return planInitialSettlement(GameStateConstants.S_SETTLEMENT2);
    }

    /**
     * Plan the first or second initial settlement.
     * Calls {@link MCTSRobotBrain#generateGame(int, int[])} and {@link MCTS#search()}.
     * Sets {@link MCTSRobotBrain#lastSettlement} to the returned node coordinate.
     *<P>
     * In StacSettlers v1, this code was part of {@link MCTSRobotBrain#placeInitialSettlement(int)}.
     * @param state {@link GameStateConstants#S_SETTLEMENT1} for first,
     *    {@link GameStateConstants#S_SETTLEMENT2} for second initial settlement
     */
    /*package*/ int planInitialSettlement(final int state) {
	final boolean isFirstSettle = (state == GameStateConstants.S_SETTLEMENT1);
	final MCTSRobotBrain br = (MCTSRobotBrain) brain;

	//try to avoid replanning if possible
	if ((firstSettlement != -1 && isFirstSettle) || (secondSettlement != -1 && ! isFirstSettle)) {
		D.ebugERROR("Player " + br.getPlayerNumber() + "was asked to place initial settlement multiple times in state " + state);
		return (isFirstSettle) ? firstSettlement : secondSettlement;
	}

	Game g = br.generateGame(state,null);
	mcts.newTree(g);
	Timer t = new Timer();
	SearchListener listener = mcts.search();
    	listener.waitForFinish();
    	int[] action = g.listPossiblities(false).getOptions().get(mcts.getNextActionIndex());

	String s = String.format("Player " + br.getPlayerNumber() + " chose initial settlement action: [%d %d %d %d %d]", action[0], action[1], action[2],
			action[3], action[4]);
	D.ebugPrintlnINFO(s);

	int lastSettlement = br.translateVertexToJSettlers(action[1]);
	br.lastSettlement = lastSettlement;

	//NOTE: JSettlers contains a bug which may result in a plannign agent being asked twice to place the initial settlement;
	if (isFirstSettle)
		firstSettlement = lastSettlement;
	else
		secondSettlement = lastSettlement;

	return lastSettlement;
    }

    /**
     * {@inheritDoc}
     *<P>
     * Calls {@link MCTSRobotBrain#generateGame(int, int[])} and {@link MCTS#search()}.
     * Clears {@link MCTSRobotBrain#lastSettlement} to -1.
     *<P>
     * In StacSettlers v1, this code was part of {@link MCTSRobotBrain#placeInitialRoad(int)}.
     */
    @Override
    public int planInitRoad() {
	final MCTSRobotBrain br = (MCTSRobotBrain) brain;
	final int state = (game.getGameState() == SOCGame.START1B)
		? GameStateConstants.S_ROAD1
		: GameStateConstants.S_ROAD2;

	Game g = br.generateGame(state,null);
	mcts.newTree(g);
	SearchListener listener = mcts.search();
	listener.waitForFinish();
	int[] action = g.listPossiblities(false).getOptions().get(mcts.getNextActionIndex());
	String s = String.format("Player " + br.getPlayerNumber() + " chose first road action: [%d %d %d %d %d]", action[0], action[1], action[2], action[3],
			action[4]);
	D.ebugPrintlnINFO(s);

	int road = br.translateEdgeToJSettlers(action[1]);
	plannedRoadDestinationNode = -1;  // we don't know where MCTS planned to go next
	br.lastSettlement = -1;

	return road;
    }

}
