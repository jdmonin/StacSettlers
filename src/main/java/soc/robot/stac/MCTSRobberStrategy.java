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
import mcts.game.catan.Catan;
import mcts.game.catan.GameStateConstants;
import mcts.listeners.SearchListener;
import soc.debug.D;
import soc.game.SOCBoard;
import soc.game.SOCGame;
import soc.game.SOCPlayer;

/**
 * The {@link RobberStrategy} for {@link MCTSRobotBrain}.
 * @since 2.4.50
 */
public class MCTSRobberStrategy extends StacRobberStrategy
{
	/**
	 * used in planning from whom to steal
	 */
	protected int robberVictim = -1;

	/**
	 * used in remembering where to place the robber after planning knight card
	 */
	protected int robberHexFromKnight = -1;

	private MCTS mcts;

	public MCTSRobberStrategy(SOCGame ga, SOCPlayer pl, MCTSRobotBrain br, MCTS mcts, Random rand) {
	    super(ga, pl, br, rand);
	    this.mcts = mcts;
	}

	/**
	 * {@inheritDoc}
	 *<P>
	 * In StacSettlers v1, this code was part of {@link MCTSRobotBrain#moveRobber()}.
	 */
	@Override
	public int getBestRobberHex() {
		final int playerNumber = ourPlayerData.getPlayerNumber();
		boolean illegal = true;
		int[] action = null;
		while (illegal) {
			illegal = false;
			Game g = ((MCTSRobotBrain) brain).generateGame(GameStateConstants.S_ROBBERAT7,null);
			mcts.newTree(g);
			SearchListener listener = mcts.search();
			listener.waitForFinish();
			action = g.listPossiblities(false).getOptions().get(mcts.getNextActionIndex());

			String s = String.format("Player " + playerNumber + " chose robber action: [%d %d %d %d %d]", action[0], action[1], action[2], action[3],
					action[4]);
			D.ebugPrintlnINFO(s);

			// Check if it is legal; the only illegal actions are if the robber
			// is moved outside of the land or on the same location or no plan
			// was made
			int tempHex = MCTSRobotBrain.translateHexToJSettlers(action[1], Catan.board);
			if (tempHex == -1) {
				illegal = true;
				D.ebugERROR("Illegal attempt to place the robber - no plan; Player " + playerNumber);
			} else if (tempHex == game.getBoard().getRobberHex()) {
				illegal = true;
				D.ebugERROR("Illegal attempt to place the robber - placing back in the same location; Player"
						+ playerNumber);
			} else if (! game.getBoard().isHexOnLand(tempHex)) {
				illegal = true;
				D.ebugERROR("Illegal attempt to place the robber - no land hex; Player" + playerNumber);
			}
		}

		robberVictim = action[2];
		int xn = (int) Catan.board.hextiles[action[1]].pos.x;
		int yn = (int) Catan.board.hextiles[action[1]].pos.y;

		int robberHex = MCTSRobotBrain.translateHexToJSettlers(action[1], Catan.board);

		D.ebugPrintlnINFO("Player " + ourPlayerData.getPlayerNumber() + " MOVE robber to hex " + robberHex + "( hex " + action[1] + ", coord: " + xn + "," + yn
			+ "), steal from" + robberVictim);

		return robberHex;
	}

	@Override
	public int chooseRobberVictim(boolean[] choices, boolean canChooseNone) {
		D.ebugPrintINFO("Player " + ourPlayerData.getPlayerNumber() + " choosing victim " + robberVictim);

		return robberVictim;
	}

}
