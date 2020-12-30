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

import mcts.MCTS;
import soc.debug.D;
import soc.game.SOCGame;
import soc.game.SOCPlayer;
import soc.robot.MonopolyStrategy;

/**
 * The {@link MonopolyStrategy} for {@link MCTSRobotBrain}.
 * Its {@link #getMonopolyChoice()} value is set by {@code MCTSRobotBrain} when MCTS decides
 * its best next action is to play a monopoly card (action {@code A_PLAYCARD_MONOPOLY}).
 * @since 2.4.50
 */
public class MCTSMonopolyStrategy extends MonopolyStrategy
{
	private MCTS mcts;

	public MCTSMonopolyStrategy(SOCGame ga, SOCPlayer pl, MCTSRobotBrain br, MCTS mcts) {
		super(ga, pl, br);
		this.mcts = mcts;
	}

	// decidePlayMonopoly() is unused by MCTSRobotBrain

}
