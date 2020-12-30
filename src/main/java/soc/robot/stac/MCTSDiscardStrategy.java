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

import mcts.MCTS;
import mcts.game.Game;
import mcts.game.catan.GameStateConstants;
import mcts.listeners.SearchListener;
import soc.debug.D;
import soc.game.SOCGame;
import soc.game.SOCPlayer;
import soc.game.SOCResourceSet;
import soc.robot.DiscardStrategy;
import soc.robot.SOCBuildPlan;

/**
 * The {@link DiscardStrategy} for {@link MCTSRobotBrain}.
 * @since 2.4.50
 */
public class MCTSDiscardStrategy extends DiscardStrategy
{
    private final MCTS mcts;

    public MCTSDiscardStrategy(SOCGame ga, SOCPlayer pl, MCTSRobotBrain br, MCTS mcts, Random rand)
    {
        super(ga, pl, br, rand);
        this.mcts = mcts;
    }

    public SOCResourceSet discard
        (final int numDiscards, SOCBuildPlan buildingPlan)
    {
	SOCResourceSet discards = new SOCResourceSet();

	Game g = ((MCTSRobotBrain) brain).generateGame(GameStateConstants.S_PAYTAX, null);
	mcts.newTree(g);
	SearchListener listener = mcts.search();
	listener.waitForFinish();
	int[] action = g.listPossiblities(false).getOptions().get(mcts.getNextActionIndex());
	String s = String.format("Player " + ourPlayerData.getPlayerNumber() + " chose discard action: [%d %d %d %d %d %d]", action[0], action[1], action[2], action[3], action[4], action[5]);
	D.ebugPrintlnINFO(s);

	for(int i=1; i < 6; i++) //jump the action description and only look at the first 6
		discards.add(action[i], MCTSRobotBrain.translateResToJSettlers(i-1)); //position is type, contents is amount

	return discards;
    }

}
