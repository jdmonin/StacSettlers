/**
 * Java Settlers - An online multiplayer version of the game Settlers of Catan
 * Copyright (C) 2003  Robert S. Thomas <thomas@infolab.northwestern.edu>
 * Portions of this file copyright (C) 2009,2012,2014-2015,2018,2020 Jeremy D Monin <jeremy@nand.net>
 * Portions of this file Copyright (C) 2012 Paul Bilnoski <paul@bilnoski.net>
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
package soc.robot;

import soc.game.SOCCity;
import soc.game.SOCPlayer;
import soc.game.SOCPlayerNumbers;


/**
 * This is a possible city that we can build
 *
 * @author Robert S Thomas
 *
 */
public class SOCPossibleCity extends SOCPossiblePiece
{
    /** Last structural change v2.4.10 (2410) */
    private static final long serialVersionUID = 2410L;

    /**
     * Speedup per building type.  Indexed from {@link SOCBuildingSpeedEstimate#MIN}
     * to {@link SOCBuildingSpeedEstimate#MAXPLUSONE}.
     */
    protected int[] speedup = { 0, 0, 0, 0, 0 };

    /**
     * Our {@link SOCBuildingSpeedEstimate} factory.
     * @since 2.4.10
     */
    protected SOCBuildingSpeedEstimateFactory bseFactory;

    /**
     * constructor
     *
     * @param pl  the owner; not null
     * @param co  coordinates; not validated
     * @param bseFactory  factory to use for {@link SOCBuildingSpeedEstimate} calls; not null
     */
    public SOCPossibleCity(SOCPlayer pl, int co, SOCBuildingSpeedEstimateFactory bseFactory)
    {
        super(SOCPossiblePiece.CITY, pl, co);

        eta = 0;
        threatUpdatedFlag = false;
        hasBeenExpanded = false;
        this.bseFactory = bseFactory;

        updateSpeedup();
    }

    /**
     * copy constructor.
     *
     * Note: This will not copy {@code pc}'s lists, only make empty ones.
     *
     * @param pc  the possible city to copy
     */
    public SOCPossibleCity(SOCPossibleCity pc)
    {
        //D.ebugPrintln(">>>> Copying possible city: "+pc);
        super(SOCPossiblePiece.CITY, pc.getPlayer(), pc.getCoordinates());

        eta = pc.getETA();
        threatUpdatedFlag = false;
        hasBeenExpanded = false;
        bseFactory = pc.bseFactory;

        int[] pcSpeedup = pc.getSpeedup();
        for (int buildingType = SOCBuildingSpeedEstimate.MIN;
                buildingType < SOCBuildingSpeedEstimate.MAXPLUSONE;
                buildingType++)
        {
            speedup[buildingType] = pcSpeedup[buildingType];
        }
    }

    /**
     * calculate the speedup that this city gives.
     * Call when any player's new city or settlement is added to the board.
     * @see #getSpeedup()
     */
    public void updateSpeedup()
    {
        //D.ebugPrintln("****************************** (CITY) updateSpeedup at "+Integer.toHexString(coord));
        SOCBuildingSpeedEstimate bse1 = bseFactory.getEstimator(player.getNumbers());
        int[] ourBuildingSpeed = bse1.getEstimatesFromNothingFast(player.getPortFlags());
        SOCPlayerNumbers newNumbers = new SOCPlayerNumbers(player.getNumbers());
        newNumbers.updateNumbers(new SOCCity(player, coord, null), player.getGame().getBoard());

        SOCBuildingSpeedEstimate bse2 = bseFactory.getEstimator(newNumbers);
        int[] speed = bse2.getEstimatesFromNothingFast(player.getPortFlags());

        for (int buildingType = SOCBuildingSpeedEstimate.MIN;
                buildingType < SOCBuildingSpeedEstimate.MAXPLUSONE;
                buildingType++)
        {
            //D.ebugPrintln("!@#$% ourBuildingSpeed[buildingType]="+ourBuildingSpeed[buildingType]+" speed[buildingType]="+speed[buildingType]);
            speedup[buildingType] = ourBuildingSpeed[buildingType] - speed[buildingType];
        }
    }

    /**
     * @return the speedup for this city
     */
    public int[] getSpeedup()
    {
        return speedup;
    }

    /**
     * @return the sum of all of the speedup numbers
     * @see #updateSpeedup()
     */
    public int getSpeedupTotal()
    {
        int sum = 0;

        for (int buildingType = SOCBuildingSpeedEstimate.MIN;
                buildingType < SOCBuildingSpeedEstimate.MAXPLUSONE;
                buildingType++)
        {
            sum += speedup[buildingType];
        }

        return sum;
    }

}
