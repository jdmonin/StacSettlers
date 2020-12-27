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

import originalsmartsettlers.boardlayout.BoardLayout;
import originalsmartsettlers.boardlayout.GameStateConstants;
import originalsmartsettlers.player.Player;
import soc.disableDebug.D;
import soc.game.SOCGame;
import soc.game.SOCPlayer;

/**
 * The {@link RobberStrategy} for {@link OriginalSSRobotBrain}.
 * @since 2.4.50
 */
public class OriginalSSRobberStrategy extends StacRobberStrategy
{

    /**
     * used in planning from whom to steal
     */
    int robberVictim = -1;

    public OriginalSSRobberStrategy(SOCGame ga, SOCPlayer pl, OriginalSSRobotBrain br, Random rand) {
        super(ga, pl, br, rand);
    }

    /**
     * {@inheritDoc}
     *<P>
     * In StacSettlers v1, this code was part of {@link OriginalSSRobotBrain#moveRobber()}.
     */
    @Override
    public int getBestRobberHex()
    {
        final OriginalSSRobotBrain br = (OriginalSSRobotBrain) brain;
        final BoardLayout bl = br.bl;
        final int playerNumber = br.getPlayerNumber();

        boolean unhandled = false;
        try {
            br.sendStateToSmartSettlers(GameStateConstants.S_ROBBERAT7);
            Player p = bl.player[playerNumber];
            bl.possibilities.Clear();
            p.listRobberPossibilities(bl.state,GameStateConstants.A_PLACEROBBER);
            p.selectAction(bl.state, bl.action);
            p.performAction(bl.state, bl.action);
        } catch (Exception e) {
            System.err.println("Unhandled exception");
            unhandled = true;
        }

        //any exceptions thrown or illegal nothing action result in a random move instead
        if(unhandled){
            br.sendStateToSmartSettlers(GameStateConstants.S_ROBBERAT7);
            Player p = bl.player[playerNumber];
            bl.possibilities.Clear();
            p.listRobberPossibilities(bl.state,GameStateConstants.A_PLACEROBBER);
            Random r = new Random();
            int aind = r.nextInt(bl.action.length);
            for (int i=0; i<bl.action.length; i++)
                bl.action[i] = bl.possibilities.action[aind][i];
            p.performAction(bl.state, bl.action);
        }
        if(bl.action[0]==GameStateConstants.A_NOTHING){
            //illegal action do a random one instead
            br.sendStateToSmartSettlers(GameStateConstants.S_ROBBERAT7);
            Player p = bl.player[playerNumber];
            bl.possibilities.Clear();
            p.listRobberPossibilities(bl.state,GameStateConstants.A_PLACEROBBER);
            Random r = new Random();
            int aind = r.nextInt(bl.action.length);
            for (int i=0; i<bl.action.length; i++)
                bl.action[i] = bl.possibilities.action[aind][i];

            p.performAction(bl.state, bl.action);
        }

        String s = String.format("Performing action: [%d %d %d %d %d]", bl.action[0], bl.action[1], bl.action[2], bl.action[3], bl.action[4]);
        D.ebugPrintlnINFO(s);

        int robberHex = br.translateHexToJSettlers(bl.action[1]);
        robberVictim = bl.action[2];
        D.ebugPrintlnINFO("!!! MOVING ROBBER !!!");
        int xn = (int) bl.hextiles[bl.action[1]].pos.x;
        int yn = (int) bl.hextiles[bl.action[1]].pos.y;

        D.ebugPrintlnINFO("MOVE robber to hex " + robberHex +"( hex " + bl.action[1] + ", coord: " + xn + "," + yn + "), steal from" + robberVictim);
        return robberHex;
    }

    @Override
    public int chooseRobberVictim(boolean[] choices, boolean canChooseNone)
    {
        return robberVictim;
    }

}
