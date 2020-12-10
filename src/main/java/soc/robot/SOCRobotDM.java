/**
 * Java Settlers - An online multiplayer version of the game Settlers of Catan
 * This file copyright (C) 2003-2004  Robert S. Thomas
 * Portions of this file copyright (C) 2009-2020 Jeremy D Monin <jeremy@nand.net>
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

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Vector;

import soc.game.SOCPlayer;
import soc.game.SOCResourceConstants;
import soc.game.SOCResourceSet;


/**
 * Moved the routines that pick what to build or buy
 * next out of SOCRobotBrain.  Didn't want
 * to call this SOCRobotPlanner because it
 * doesn't really plan, but you could think
 * of it that way.  DM = Decision Maker
 *<P>
 * Uses the info in the {@link SOCPlayerTracker}s.
 * One important method here is {@link #planStuff(int)},
 * which updates {@link #buildingPlan} and related fields.
 *
 * @author Robert S. Thomas
 */

public abstract class SOCRobotDM<BP extends SOCBuildPlan>
{
////Fields moved from SOCBrain//////	
    /**
     * these are the two resources that we want
     * when we play a discovery dev card
     */
    public SOCResourceSet resourceChoices;
	
////Fields moved from SOCBrain//////

    /*
     * Variables for affecting the decision making which can be redefined depending on the robot's type
     */
    protected int maxGameLength = 300;

    protected int maxETA = 99;

    protected float etaBonusFactor = (float)0.8;

    protected float adversarialFactor = (float)1.5;

    protected float leaderAdversarialFactor = (float)3.0;

    protected float devCardMultiplier = (float)2.0;

    protected float threatMultiplier = (float)1.1;

    /*
     * Constants for defining choices
     */
    protected static final int LA_CHOICE = 0;
    protected static final int LR_CHOICE = 1;
    protected static final int CITY_CHOICE = 2;
    protected static final int SETTLEMENT_CHOICE = 3;
    protected static final DecimalFormat df1 = new DecimalFormat("###0.00");
    
    /*
     * Fields used only internally and inherited by all subclasses. 
     * These should be private, however it is impossible to inherit private fields.
     * player, playerTrackers, ourPlayerTracker, and buildingPlan are references to the same objects stored in the Brain object
     */
    protected SOCPlayer player;

  /**
   * Player trackers, one per player number; vacant seats are null.
   * Same format as {@link SOCRobotBrain#getPlayerTrackers()}.
   * @see #ourPlayerTracker
   */
  protected SOCPlayerTracker[] playerTrackers;

  /** Player tracker for {@link #ourPlayerData}. */
  protected SOCPlayerTracker ourPlayerTracker;

  /**
   * {@link #ourPlayerData}'s player number.
   * @since 2.0.00
   */
  protected final int ourPlayerNumber;

  /**
   * {@link #ourPlayerData}'s building plan.
   * Same Stack as {@link SOCRobotBrain#getBuildingPlan()}.
   * May include {@link SOCPossibleCard} to be bought.
   * Filled each turn by {@link #planStuff(int)}.
   * Emptied by {@link SOCRobotBrain}'s calls to {@link SOCRobotBrain#resetBuildingPlan()}.
   *<P>
   * Before v2.4.50 this was an unencapsulated Stack of {@link SOCPossiblePiece}.
   */
  protected SOCBuildPlanStack buildingPlan;

  /**
   * Strategy to plan and build initial settlements and roads.
   * Used here for {@link OpeningBuildStrategy#estimateResourceRarity()}.
   * @since 2.4.50
   */
  protected final OpeningBuildStrategy openingBuildStrategy;

  /**
   * Our {@link SOCBuildingSpeedEstimate} factory.
   * Is set during construction, from {@link SOCRobotBrain#createEstimatorFactory()} if available.
   * @see #getEstimatorFactory()
   * @since 2.4.50
   */
  protected SOCBuildingSpeedEstimateFactory bseFactory;

  /** Roads threatened by other players; currently unused. */
  protected final ArrayList<SOCPossibleRoad> threatenedRoads;

  /**
   * A road or ship ({@link SOCPossibleRoad} and/or subclass {@link SOCPossibleShip})
   * we could build this turn; its {@link SOCPossibleRoad#getNecessaryRoads()} is empty.
   * Built in {@link #smartGameStrategy(int[])}.
   */
  protected final ArrayList<SOCPossibleRoad> goodRoads;

  /**
   * A road or ship we could build this turn, chosen
   * from {@link #threatenedRoads} or {@link #goodRoads}
   * in {@link #smartGameStrategy(int[])}.
   * If we want to build this soon, it will be added to {@link #buildingPlan}.
   */
  protected SOCPossibleRoad favoriteRoad;

  /** Threatened settlements, as calculated by {@link #scorePossibleSettlements(int, int)} */
  protected final ArrayList<SOCPossibleSettlement> threatenedSettlements;

  /** Good settlements, as calculated by {@link #scorePossibleSettlements(int, int)} */
  protected final ArrayList<SOCPossibleSettlement> goodSettlements;

  /**
   * A settlement to build, chosen from {@link #goodSettlements} or {@link #threatenedSettlements}.
   * If we want to build this soon, it will be added to {@link #buildingPlan}.
   */
  protected SOCPossibleSettlement favoriteSettlement;

    protected SOCPossibleCity favoriteCity;
    protected SOCPossibleCard possibleCard;
    protected int strategy;
    
    /*
     * used for describing the depth of the search (i.e. fast is 1-ply search, smart is 2-ply)
     */
    public static final int SMART_STRATEGY = 0;
    public static final int FAST_STRATEGY = 1;
    
	public SOCRobotDM() {
        //why are these set to clay or sheep ????
		resourceChoices = new SOCResourceSet();
        resourceChoices.add(2, SOCResourceConstants.CLAY);
	}
	
	/**
	 * Method that performs the search and generates the buildingPlan 
	 */
	public abstract void planStuff();
    
    /**
     * Should the player play a knight for the purpose of working towards largest army? 
     * If we already have largest army, should we now defend it if another player is close to taking it from us?
     * Called during game state {@link SOCGame#PLAY1} when we have at least 1 {@link SOCDevCardConstants#KNIGHT}
     * available to play, and haven't already played a dev card this turn.
     * @return  true if knight should be played now, not kept for when it's needed later
     */
    public abstract boolean shouldPlayKnightForLA();
    
    /**
     * Should the player play a knight for the purpose of clearing a resource/robbing or blocking a competitor/etc
     * Potentially different behavior whether we've rolled or not (eg it's unlikely you'd want to rob if doing so puts you over 7 cards)
     * @return
     */
    public abstract boolean shouldPlayKnight(boolean hasRolled);
    
    /**
     * Should we play a roadbuilding card?  Assumes that we have a plan that requires a road.  May want to double check there isn't
     * a better card to play within this, for example.
     */
    public abstract boolean shouldPlayRoadbuilding();
    
    /**
     * Should we play a YOP card?  
     * @return
     */
    public abstract boolean shouldPlayDiscovery();
    
    /**
     * When playing a YOP card, set resourceChoices
     */
    public abstract void chooseFreeResources(BP buildingPlan);
    
    /**
     * Choose the resources we need most, for playing a Discovery development card
     * or when a Gold Hex number is rolled.
     * Find the most needed resource by looking at
     * which of the resources we still need takes the
     * longest to acquire, then add to {@link #resourceChoices}.
     * Looks at our player's current resources.
     *
     * @param targetResources  Resources needed to build our next planned piece,
     *             from {@link SOCPossiblePiece#getResourcesToBuild()}
     *             for {@link #buildingPlan}.peek()
     * @param numChoose  Number of resources to choose
     * @param clearResChoices  If true, clear {@link #resourceChoices} before choosing what to add to it;
     *             set false if calling several times to iteratively build up a big choice.
     * @return  True if we could choose <tt>numChoose</tt> resources towards <tt>targetResources</tt>,
     *             false if we could fully satisfy <tt>targetResources</tt>
     *             from our current resources + less than <tt>numChoose</tt> more.
     *             Examine {@link #resourceChoices}{@link SOCResourceSet#getTotal() .getTotal()}
     *             to see how many were chosen.
     * @see #chooseFreeResourcesIfNeeded(SOCResourceSet, int, boolean)
     * @see #getResourceChoices()
     */
    public abstract boolean chooseFreeResources
        (final SOCResourceSet targetResources, final int numChoose, final boolean clearResChoices);

  /**
   * Do we need to acquire at least <tt>numChoose</tt> resources to build our next piece?
   * Choose the resources we need most; used when we want to play a discovery development card
   * or when a Gold Hex number is rolled.
   * If returns true, has called {@link #chooseFreeResources(SOCResourceSet, int, boolean)}
   * and has set {@link #resourceChoices}.
   *<P>
   * Before v2.4.50, this method was in {@code SOCRobotBrain}.
   *
   * @param targetResources  Resources needed to build our next planned piece,
   *             from {@link SOCPossiblePiece#getResourcesToBuild()}
   *             for {@link #buildingPlan}.
   *             If {@code null}, returns false (no more resources required).
   * @param numChoose  Number of resources to choose
   * @param chooseIfNotNeeded  Even if we find we don't need them, choose anyway;
   *             set true for Gold Hex choice, false for Discovery card pick.
   * @return  true if we need <tt>numChoose</tt> resources
   * @since 2.0.00
   */
  public boolean chooseFreeResourcesIfNeeded
      (SOCResourceSet targetResources, final int numChoose, final boolean chooseIfNotNeeded)
  {
      if (targetResources == null)
          return false;

      if (chooseIfNotNeeded)
          resourceChoices.clear();

      final SOCResourceSet ourResources = ourPlayerData.getResources();
      int numMore = numChoose;

      // Used only if chooseIfNotNeeded:
      int buildingItem = 0;  // for ourBuildingPlan.peek
      boolean stackTopIs0 = false;

      /**
       * If ! chooseIfNotNeeded, this loop
       * body will only execute once.
       */
      do
      {
          int numNeededResources = 0;
          if (targetResources == null)  // can be null from SOCPossiblePickSpecialItem.cost
              break;

          for (int resource = SOCResourceConstants.CLAY;
                  resource <= SOCResourceConstants.WOOD;
                  resource++)
          {
              final int diff = targetResources.getAmount(resource) - ourResources.getAmount(resource);
              if (diff > 0)
                  numNeededResources += diff;
          }

          if ((numNeededResources == numMore)  // TODO >= numMore ? (could change details of current bot behavior)
              || (chooseIfNotNeeded && (numNeededResources > numMore)))
          {
              chooseFreeResources(targetResources, numMore, ! chooseIfNotNeeded);
              return true;
          }

          if (! chooseIfNotNeeded)
              return false;

          // Assert: numNeededResources < numMore.
          // Pick the first numNeeded, then loop to pick additional ones.
          chooseFreeResources(targetResources, numMore, false);
          numMore = numChoose - resourceChoices.getTotal();

          if (numMore > 0)
          {
              // Pick a new target from building plan, if we can.
              // Otherwise, choose our least-frequently-rolled resources.

              ++buildingItem;
              final int bpSize = buildingPlan.size();
              if (bpSize > buildingItem)
              {
                  if (buildingItem == 1)
                  {
                      // validate direction of stack growth for buildingPlan
                      stackTopIs0 = (0 == buildingPlan.indexOf(buildingPlan.getPlannedPiece(0)));
                  }

                  int i = (stackTopIs0) ? buildingItem : (bpSize - buildingItem) - 1;

                  SOCPossiblePiece targetPiece = buildingPlan.elementAt(i);
                  targetResources = targetPiece.getResourcesToBuild();  // may be null

                  // Will continue at top of loop to add
                  // targetResources to resourceChoices.

              } else {

                  // This will be the last iteration.
                  // Choose based on our least-frequent dice rolls.

                  final int[] resourceOrder =
                      bseFactory.getRollsForResourcesSorted(ourPlayerData);

                  int curRsrc = 0;
                  while (numMore > 0)
                  {
                      resourceChoices.add(1, resourceOrder[curRsrc]);
                      --numMore;
                      ++curRsrc;
                      if (curRsrc == resourceOrder.length)
                          curRsrc = 0;
                  }

                  // now, numMore == 0, so do-while loop will exit at bottom.
              }
          }

      } while (numMore > 0);

      return true;
  }

    /**
     * Get the resources we want to request when we play a discovery dev card.
     * Third-party bots might sometimes want to change the contents of this set.
     *
     * @return This DM's resource set to request for a Discovery dev card;
     *     never null, but may be empty
     * @see #chooseFreeResources(SOCResourceSet, int, boolean)
     * @see #pickFreeResources(int)
     * @since 2.4.50
     */
    public SOCResourceSet getResourceChoices()
    {
        return resourceChoices;
    }

  /**
   * Respond to server's request to pick resources to gain from the Gold Hex.
   * Use {@link #buildingPlan} or, if that's empty (like during initial placement),
   * pick what's rare from {@link OpeningBuildStrategy#estimateResourceRarity()}.
   *<P>
   * Caller may want to check if {@link #buildingPlan} is empty, and
   * call {@link SOCRobotBrain#planBuilding()} if so, before calling this method.
   *<P>
   * Before v2.4.50, this method was in {@code SOCRobotBrain}.
   *
   * @param numChoose  Number of resources to pick
   * @return  the chosen resource picks; also sets {@link #getResourceChoices()} to the returned set
   * @since 2.0.00
   */
  protected SOCResourceSet pickFreeResources(int numChoose)
  {
      SOCResourceSet targetResources;

      if (! buildingPlan.isEmpty())
      {
          final SOCPossiblePiece targetPiece = buildingPlan.peek();
          targetResources = targetPiece.getResourcesToBuild();  // may be null
          chooseFreeResourcesIfNeeded(targetResources, numChoose, true);
      } else {
          // Pick based on board dice-roll rarities.
          // TODO: After initial placement, consider based on our
          // number probabilities based on settlements/cities placed.
          //  (BSE.getRollsForResourcesSorted)

          resourceChoices.clear();
          final int[] resourceEstimates = openingBuildStrategy.estimateResourceRarity();
          int numEach = 0;  // in case we pick 5, keep going for 6-10
          while (numChoose > 0)
          {
              int res = -1, pct = Integer.MAX_VALUE;
              for (int i = SOCBoard.CLAY_HEX; i <= SOCBoard.WOOD_HEX; ++i)
              {
                  if ((resourceEstimates[i] < pct) && (resourceChoices.getAmount(i) < numEach))
                  {
                      res = i;
                      pct = resourceEstimates[i];
                  }
              }
              if (res != -1)
              {
                  resourceChoices.add(1, res);
                  --numChoose;
              } else {
                  ++numEach;  // has chosen all 5 by now
              }
          }
      }

      return resourceChoices;
  }

  /**
   * Estimator factory method for when a player's dice numbers are known.
   * Calls {@link SOCRobotBrain#getEstimator(SOCPlayerNumbers)} if DM has non-null {@link #brain} field.
   * If brain not available (simulation situations, etc), constructs a new Estimate with
   * {@link #getEstimatorFactory()}.{@link SOCBuildingSpeedEstimateFactory#getEstimator(SOCPlayerNumbers) getEstimator(numbers)}.
   *<P>
   * This factory may be overridden by third-party bot DMs. However, it's
   * also not unreasonable to expect that simulation of opponent planning
   * would involve a less exact estimation than considering our own plans.
   *
   * @param numbers Player's dice numbers to start from,
   *     in same format passed into {@link SOCBuildingSpeedEstimate#SOCBuildingSpeedEstimate(SOCPlayerNumbers)}
   * @return  Estimator based on {@code numbers}
   * @see #getEstimator()
   * @since 2.4.50
   */
  protected SOCBuildingSpeedEstimate getEstimator(SOCPlayerNumbers numbers)
  {
      return bseFactory.getEstimator(numbers);
  }

  /**
   * Estimator factory method for when a player's dice numbers are unknown or don't matter yet.
   * Calls {@link SOCRobotBrain#getEstimator()} if DM has non-null {@link #brain} field.
   * If brain not available (simulation situations, etc), constructs a new Estimate with
   * {@link #getEstimatorFactory()}.{@link SOCBuildingSpeedEstimateFactory#getEstimator() getEstimator()}.
   *<P>
   * This factory may be overridden by third-party bot DMs. However, it's
   * also not unreasonable to expect that simulation of opponent planning
   * would involve a less exact estimation than considering our own plans.
   *
   * @return  Estimator which doesn't consider player's dice numbers yet;
   *     see {@link SOCBuildingSpeedEstimate#SOCBuildingSpeedEstimate()} javadoc
   * @see #getEstimator(SOCPlayerNumbers)
   * @since 2.4.50
   */
  protected SOCBuildingSpeedEstimate getEstimator()
  {
      return bseFactory.getEstimator();
  }

  /**
   * Get this decision maker's {@link SOCBuildingSpeedEstimate} factory.
   * Is set in constructor, from our brain or otherwise.
   *
   * @return This decision maker's factory
   * @see #getEstimator(SOCPlayerNumbers)
   * @since 2.4.50
   */
  public SOCBuildingSpeedEstimateFactory getEstimatorFactory()
  {
      return bseFactory;
  }

}		







