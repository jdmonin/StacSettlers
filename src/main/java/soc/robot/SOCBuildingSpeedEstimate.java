/**
 * Java Settlers - An online multiplayer version of the game Settlers of Catan
 * Copyright (C) 2003  Robert S. Thomas <thomas@infolab.northwestern.edu>
 * Portions of this file copyright (C) 2012-2013,2015-2018,2020 Jeremy D Monin <jeremy@nand.net>
 * Portions of this file Copyright (C) 2012 Paul Bilnoski <paul@bilnoski.net>
 * Portions of this file Copyright (C) 2017 Ruud Poutsma <rtimon@gmail.com>
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
package soc.robot;

import soc.disableDebug.D;

import soc.game.*;

import soc.util.CutoffExceededException;

import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;


/**
 * This class calculates approximately how
 * long it would take a player to build something.
 * Uses {@link SOCPlayerNumbers} to get resources of currently reached hexes.
 * The {@code getEstimates...} methods use {@link SOCPlayer#getPortFlags()}.
 * Used by {@link SOCRobotDM#planStuff(int)} and other tactical planning methods.
 *<P>
 * Robot typically uses factory methods like {@link SOCRobotBrain#getEstimator(SOCPlayerNumbers)}
 * and {@link SOCBuildingSpeedEstimateFactory#getEstimator(SOCPlayerNumbers)}
 * instead of directly instantiating this class.
 *<P>
 * This has been refactored to be abstract and allow implementations 
 * Note that for the sake of minimizing refactoring, functions 
 *  are referred to as "Fast" in all cases.  TBD whether it's worth 
 *  renaming.
 */
public class SOCBuildingSpeedEstimate
{
    public static final int ROAD = 0;
    public static final int SETTLEMENT = 1;
    public static final int CITY = 2;
    public static final int CARD = 3;
    public static final int SHIP = 4;
    public static final int MIN = 0;
    public static final int MAXPLUSONE = 5;
    public static final int DEFAULT_ROLL_LIMIT = 40;
    protected static boolean recalc;
    int[] estimatesFromNothing;
    int[] estimatesFromNow;

    /**
     * Number of rolls to gain each resource type ({@link SOCResourceConstants#CLAY}
     * to {@link SOCResourceConstants#WOOD}).
     * Index 0 is unused.
     *<P>
     * Does not contain {@link soc.game.SOCBoardLarge#GOLD_HEX GOLD_HEX}
     * or {@link SOCResourceConstants#GOLD_LOCAL},
     * because {@link SOCPlayerNumbers} methods translate the gold hexes into
     * each of the normal 5 resource types.
     */
    private int[] rollsPerResource;

    /**
     * Resource sets gained for each dice roll number (2 to 12).
     * Indexes 0 and 1 are unused.
     *<P>
     * Does not contain {@link soc.game.SOCBoardLarge#GOLD_HEX GOLD_HEX}
     * or {@link SOCResourceConstants#GOLD_LOCAL},
     * because {@link SOCPlayerNumbers} methods translate each gold hex number
     * into 1 resource of each of the normal 5 types.
     */
    private SOCResourceSet[] resourcesForRoll;

    /**
     * Create a new SOCBuildingSpeedEstimate, calculating
     * the rollsPerResource and resourcesPerRoll based on
     * the player's dice numbers (settlement/city hexes).
     *
     * @param numbers  the numbers that the player's pieces are touching
     */
    public SOCBuildingSpeedEstimate(SOCPlayerNumbers numbers)
    {
        estimatesFromNothing = new int[MAXPLUSONE];
        estimatesFromNow = new int[MAXPLUSONE];
        rollsPerResource = new int[SOCResourceConstants.WOOD + 1];
        recalculateRollsPerResource(numbers, -1);
        resourcesForRoll = new SOCResourceSet[13];
        recalculateResourcesForRoll(numbers, -1);
    }

    /**
     * Create a new SOCBuildingSpeedEstimate, not yet calculating
     * estimates.  To consider the player's dice numbers (settlement/city hexes),
     * you'll need to call {@link #recalculateEstimates(SOCPlayerNumbers, int)}.
     */
    public SOCBuildingSpeedEstimate()
    {
        estimatesFromNothing = new int[MAXPLUSONE];
        estimatesFromNow = new int[MAXPLUSONE];
        rollsPerResource = new int[SOCResourceConstants.WOOD + 1];
        resourcesForRoll = new SOCResourceSet[13];
    }

    /**
     * Estimate the rolls for this player to obtain each resource.
     * Will construct a <tt>SOCBuildingSpeedEstimate</tt>
     * from {@link SOCPlayer#getNumbers() pl.getNumbers()},
     * and call {@link #getRollsPerResource()}.
     * @param pl  Player to check numbers
     * @return  Resource order, sorted by rolls per resource descending;
     *        a 5-element array containing
     *        {@link SOCResourceConstants#CLAY},
     *        {@link SOCResourceConstants#WHEAT}, etc,
     *        where the resource type constant in [0] has the highest rolls per resource.
     * @since 2.0.00
     */
    public static final int[] getRollsForResourcesSorted(final SOCPlayer pl)
    {
        SOCBuildingSpeedEstimate estimate = new SOCBuildingSpeedEstimate(pl.getNumbers());
        final int[] rollsPerResource = estimate.getRollsPerResource();
        int[] resourceOrder =
        {
            SOCResourceConstants.CLAY, SOCResourceConstants.ORE,
            SOCResourceConstants.SHEEP, SOCResourceConstants.WHEAT,
            SOCResourceConstants.WOOD
        };

        // Sort descending; resourceOrder[0] will have the highest rollsPerResource.
        for (int j = 4; j >= 0; j--)
        {
            for (int i = 0; i < j; i++)
            {
                if (rollsPerResource[resourceOrder[i]] < rollsPerResource[resourceOrder[i + 1]])
                {
                    int tmp = resourceOrder[i];
                    resourceOrder[i] = resourceOrder[i + 1];
                    resourceOrder[i + 1] = tmp;
                }
            }
        }

        return resourceOrder;
    }

    /**
     * @return the estimates from nothing
     *
     * @param ports  the player's trade port flags, from {@link SOCPlayer#getPortFlags()}
     */
    public int[] getEstimatesFromNothingFast(boolean[] ports)
    {
        if (recalc)
        {
            estimatesFromNothing[ROAD] = DEFAULT_ROLL_LIMIT;
            estimatesFromNothing[SETTLEMENT] = DEFAULT_ROLL_LIMIT;
            estimatesFromNothing[CITY] = DEFAULT_ROLL_LIMIT;
            estimatesFromNothing[CARD] = DEFAULT_ROLL_LIMIT;
            estimatesFromNothing[SHIP] = DEFAULT_ROLL_LIMIT;

            try
            {
                estimatesFromNothing[ROAD] = calculateRollsAndRsrcFast
                    (SOCResourceSet.EMPTY_SET, SOCRoad.COST, DEFAULT_ROLL_LIMIT, ports).getRolls();
                estimatesFromNothing[SETTLEMENT] = calculateRollsAndRsrcFast
                    (SOCResourceSet.EMPTY_SET, SOCSettlement.COST, DEFAULT_ROLL_LIMIT, ports).getRolls();
                estimatesFromNothing[CITY] = calculateRollsAndRsrcFast
                    (SOCResourceSet.EMPTY_SET, SOCCity.COST, DEFAULT_ROLL_LIMIT, ports).getRolls();
                estimatesFromNothing[CARD] = calculateRollsAndRsrcFast
                    (SOCResourceSet.EMPTY_SET, SOCDevCard.COST, DEFAULT_ROLL_LIMIT, ports).getRolls();
                estimatesFromNothing[SHIP] = calculateRollsAndRsrcFast
                    (SOCResourceSet.EMPTY_SET, SOCShip.COST, DEFAULT_ROLL_LIMIT, ports).getRolls();
            }
            catch (CutoffExceededException e)
            {
                ;
            }
        }

        return estimatesFromNothing;
    }

    /**
     * @return the estimates from nothing
     *
     * @param ports  the player's trade port flags, from {@link SOCPlayer#getPortFlags()}
     */
    public int[] getEstimatesFromNothingFast(boolean[] ports, int limit)
    {
        if (recalc)
        {
            estimatesFromNothing[ROAD] = limit;
            estimatesFromNothing[SETTLEMENT] = limit;
            estimatesFromNothing[CITY] = limit;
            estimatesFromNothing[CARD] = limit;
            estimatesFromNothing[SHIP] = limit;

            try
            {
                estimatesFromNothing[ROAD] = calculateRollsAndRsrcFast
                    (SOCResourceSet.EMPTY_SET, SOCRoad.COST, limit, ports).getRolls();
                estimatesFromNothing[SETTLEMENT] = calculateRollsAndRsrcFast
                    (SOCResourceSet.EMPTY_SET, SOCSettlement.COST, limit, ports).getRolls();
                estimatesFromNothing[CITY] = calculateRollsAndRsrcFast
                    (SOCResourceSet.EMPTY_SET, SOCCity.COST, limit, ports).getRolls();
                estimatesFromNothing[CARD] = calculateRollsAndRsrcFast
                    (SOCResourceSet.EMPTY_SET, SOCDevCard.COST, limit, ports).getRolls();
                estimatesFromNothing[SHIP] = calculateRollsAndRsrcFast
                    (SOCResourceSet.EMPTY_SET, SOCShip.COST, limit, ports).getRolls();
            }
            catch (CutoffExceededException e)
            {
                ;
            }
        }

        return estimatesFromNothing;
    }

    /**
     * @return the estimates from now
     *
     * @param resources  the player's current resources
     * @param ports      the player's trade port flags, from {@link SOCPlayer#getPortFlags()}
     */
    public int[] getEstimatesFromNowFast(SOCResourceSet resources, boolean[] ports)
    {
        estimatesFromNow[ROAD] = DEFAULT_ROLL_LIMIT;
        estimatesFromNow[SETTLEMENT] = DEFAULT_ROLL_LIMIT;
        estimatesFromNow[CITY] = DEFAULT_ROLL_LIMIT;
        estimatesFromNow[CARD] = DEFAULT_ROLL_LIMIT;
        estimatesFromNow[SHIP] = DEFAULT_ROLL_LIMIT;

        try
        {
            estimatesFromNow[ROAD] = calculateRollsAndRsrcFast(resources, SOCRoad.COST, DEFAULT_ROLL_LIMIT, ports).getRolls();
            estimatesFromNow[SETTLEMENT] = calculateRollsAndRsrcFast(resources, SOCSettlement.COST, DEFAULT_ROLL_LIMIT, ports).getRolls();
            estimatesFromNow[CITY] = calculateRollsAndRsrcFast(resources, SOCCity.COST, DEFAULT_ROLL_LIMIT, ports).getRolls();
            estimatesFromNow[CARD] = calculateRollsAndRsrcFast(resources, SOCDevCard.COST, DEFAULT_ROLL_LIMIT, ports).getRolls();
            estimatesFromNow[SHIP] = calculateRollsAndRsrcFast(resources, SOCShip.COST, DEFAULT_ROLL_LIMIT, ports).getRolls();
        }
        catch (CutoffExceededException e)
        {
            ;
        }

        return estimatesFromNow;
    }

    /**
     * recalculate both rollsPerResource and resourcesPerRoll
     * @param numbers    the numbers that the player is touching
     * @see #recalculateEstimates(SOCPlayerNumbers, int)
     */
    public void recalculateEstimates(SOCPlayerNumbers numbers)
    {
        recalculateRollsPerResource(numbers, -1);
        recalculateResourcesForRoll(numbers, -1);
    }

    /**
     * Recalculate both rollsPerResource and resourcesPerRoll,
     * optionally considering the robber's location.
     * @param numbers    the numbers that the player is touching
     * @param robberHex  Robber location from {@link SOCBoard#getRobberHex()},
     *                     or -1 to ignore the robber
     * @see #recalculateEstimates(SOCPlayerNumbers)
     */
    public void recalculateEstimates(SOCPlayerNumbers numbers, int robberHex)
    {
        recalculateRollsPerResource(numbers, robberHex);
        recalculateResourcesForRoll(numbers, robberHex);
    }

    /**
     * Calculate the rollsPerResource estimates,
     * optionally considering the robber's location.
     *
     * @param numbers    the numbers that the player is touching
     * @param robberHex  Robber location from {@link SOCBoard#getRobberHex()},
     *                     or -1 to ignore the robber
     */
    public void recalculateRollsPerResource(SOCPlayerNumbers numbers, final int robberHex)
    {
        //D.ebugPrintln("@@@@@@@@ recalculateRollsPerResource");
        //D.ebugPrintln("@@@@@@@@ numbers = " + numbers);
        //D.ebugPrintln("@@@@@@@@ robberHex = " + Integer.toHexString(robberHex));
        recalc = true;

        /**
         * figure out how many resources we get per roll
         */
        for (int resource = SOCResourceConstants.CLAY;
                resource <= SOCResourceConstants.WOOD; resource++)
        {
            //D.ebugPrintln("resource: " + resource);

            float totalProbability = 0.0f;

            Enumeration<Integer> numbersEnum =
                ((robberHex != -1)
                   ? numbers.getNumbersForResource(resource, robberHex)
                   : numbers.getNumbersForResource(resource)
                 ).elements();

            while (numbersEnum.hasMoreElements())
            {
                Integer number = numbersEnum.nextElement();
                totalProbability += SOCNumberProbabilities.FLOAT_VALUES[number.intValue()];
            }

            //D.ebugPrintln("totalProbability: " + totalProbability);

            if (totalProbability != 0.0f)
            {
                rollsPerResource[resource] = Math.round(1.0f / totalProbability);
            }
            else
            {
                rollsPerResource[resource] = 55555;
            }

            //D.ebugPrintln("rollsPerResource: " + rollsPerResource[resource]);
        }
    }

    /**
     * Calculate what resources this player will get on each
     * die roll, optionally taking the robber into account.
     *
     * @param numbers  the numbers that the player is touching
     * @param robberHex  Robber location from {@link SOCBoard#getRobberHex()},
     *                     or -1 to ignore the robber
     */
    public void recalculateResourcesForRoll(SOCPlayerNumbers numbers, final int robberHex)
    {
        //D.ebugPrintln("@@@@@@@@ recalculateResourcesForRoll");
        //D.ebugPrintln("@@@@@@@@ numbers = "+numbers);
        //D.ebugPrintln("@@@@@@@@ robberHex = "+Integer.toHexString(robberHex));
        recalc = true;

        for (int diceResult = 2; diceResult <= 12; diceResult++)
        {
            Vector<Integer> resources = (robberHex != -1)
                ? numbers.getResourcesForNumber(diceResult, robberHex)
                : numbers.getResourcesForNumber(diceResult);

            if (resources != null)
            {
                SOCResourceSet resourceSet = resourcesForRoll[diceResult];

                if (resourceSet == null)
                {
                    resourceSet = new SOCResourceSet();
                    resourcesForRoll[diceResult] = resourceSet;
                }
                else
                {
                    resourceSet.clear();
                }

                Enumeration<Integer> resourcesEnum = resources.elements();

                while (resourcesEnum.hasMoreElements())
                {
                    Integer resourceInt = resourcesEnum.nextElement();
                    resourceSet.add(1, resourceInt.intValue());
                }

                //D.ebugPrintln("### resources for "+diceResult+" = "+resourceSet);
            }
        }
    }

    /**
     * Get the number of rolls to gain each resource type ({@link SOCResourceConstants#CLAY}
     * to {@link SOCResourceConstants#WOOD}).
     *<P>
     * Does not contain {@link soc.game.SOCBoardLarge#GOLD_HEX GOLD_HEX}
     * or {@link SOCResourceConstants#GOLD_LOCAL},
     * because {@link SOCPlayerNumbers} methods translate the gold hexes into
     * each of the normal 5 resource types.
     *
     * @return the rolls per resource results; index 0 is unused.
     */
    public int[] getRollsPerResource()
    {
        return rollsPerResource;
    }

    /**
     * Figures out how many rolls it would take this
     * player to get the target set of resources, given
     * a starting set.
     *<P>
     * This method does the same calculation as
     * {@link #calculateRollsAndRsrcFast(ResourceSet, SOCResourceSet, int, boolean[])}
     * with a simpler return type and no thrown exception.
     *
     * @param startingResources   the starting resources; is treated as read-only
     * @param targetResources     the target resources; is treated as read-only
     * @param cutoff              maximum number of rolls
     * @param ports               a list of port flags
     *
     * @return  the number of rolls, or {@code cutoff} if that maximum is reached.
     *     If {@link SOCResourceSet#contains(SOCResourceSet) startingResources.contains(targetResources)},
     *     returns 0.
     * @since 2.0.00
     */
    public final int calculateRollsFast
        (final ResourceSet startingResources, final SOCResourceSet targetResources, final int cutoff, final boolean[] ports)
    {
        try
        {
            SOCResSetBuildTimePair pair = calculateRollsAndRsrcFast(startingResources, targetResources, cutoff, ports);
            return pair.getRolls();
        }
        catch (CutoffExceededException e)
        {
            return cutoff;
        }
    }

    /**
     * this figures out how many rolls it would take this
     * player to get the target set of resources given
     * a starting set
     *<P>
     * Before v2.0.00, this was {@code calculateRollsFast}.
     *
     * @param startingResources   the starting resources; is treated as read-only
     * @param targetResources     the target resources; is treated as read-only
     * @param cutoff              throw an exception if the total speed is greater than this
     * @param ports               a list of port flags
     *
     * @return the number of rolls, and startingResources after any trading.
     *     If {@link SOCResourceSet#contains(SOCResourceSet) startingResources.contains(targetResources)},
     *     returns 0 rolls and a copy of {@code startingResources} with identical amounts.
     * @throws CutoffExceededException  if total number of rolls &gt; {@code cutoff}
     * @see #calculateRollsFast(SOCResourceSet, SOCResourceSet, int, boolean[])
     */
    public abstract SOCResSetBuildTimePair calculateRollsAndRsrcFast
        (final ResourceSet startingResources, final SOCResourceSet targetResources, final int cutoff, final boolean[] ports)
        throws CutoffExceededException;

}
