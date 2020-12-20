/**
 * Java Settlers - An online multiplayer version of the game Settlers of Catan
 * Copyright (C) 2003  Robert S. Thomas <thomas@infolab.northwestern.edu>
 * Portions of this file Copyright (C) 2007-2020 Jeremy D Monin <jeremy@nand.net>
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

import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Stack;
import java.util.Vector;

import soc.baseclient.SOCDisplaylessPlayerClient;
import soc.disableDebug.D;
import soc.game.SOCBoard;
import soc.game.SOCBoardLarge;
import soc.game.SOCCity;
import soc.game.SOCDevCardConstants;
import soc.game.SOCGame;
import soc.game.SOCInventory;
import soc.game.SOCPlayer;
import soc.game.SOCPlayerNumbers;
import soc.game.SOCPlayingPiece;
import soc.game.SOCResourceConstants;
import soc.game.SOCResourceSet;
import soc.game.SOCRoad;
import soc.game.SOCSettlement;
import soc.game.SOCTradeOffer;
import soc.message.SOCAcceptOffer;
import soc.message.SOCCancelBuildRequest;
import soc.message.SOCChoosePlayerRequest;
import soc.message.SOCClearOffer;
import soc.message.SOCCollectData;
import soc.message.SOCDevCardAction;
import soc.message.SOCDevCardCount;
import soc.message.SOCDiceResult;
import soc.message.SOCDiscardRequest;
import soc.message.SOCGameState;
import soc.message.SOCGameStats;
import soc.message.SOCGameTextMsg;
import soc.message.SOCMakeOffer;
import soc.message.SOCMessage;
import soc.message.SOCMoveRobber;
import soc.message.SOCParseResult;
import soc.message.SOCPlayerElement;
import soc.message.SOCPutPiece;
import soc.message.SOCRejectOffer;
import soc.message.SOCResourceCount;
import soc.message.SOCSetPlayedDevCard;
import soc.message.SOCTurn;
import soc.robot.stac.StacRobotBrain;
import soc.robot.stac.StacRobotDeclarativeMemory;
import soc.robot.stac.simulation.Simulation;
import soc.server.SOCServer;
import soc.server.database.stac.ExtGameStateRow;
import soc.server.database.stac.StacDBHelper;
import soc.util.CappedQueue;
import soc.util.DebugRecorder;
import soc.util.Queue;
import soc.util.SOCRobotParameters;


/**
 * AI for playing Settlers of Catan.
 * Represents a robot player within 1 game.
 * 
 * Refactored partially.  This class should handle message processing and game state
 * tracking, while implementations and DM objects handle actual agent decisions.
 *<P>
 * The bot is a separate thread, so everything happens in {@link #run()} or a method called from there.
 *<P>
 * Some robot behaviors are altered by the {@link SOCRobotParameters} passed into our constructor.
 * Some decision-making code is in the {@link OpeningBuildStrategy},
 * {@link RobberStrategy}, {@link MonopolyStrategy}, etc classes.
 * Data and predictions about the other players in the game is in
 * {@link SOCPlayerTracker}.  If we're trading with other players for
 * resources, some details of that are in {@link SOCRobotNegotiator}.
 * All these, and data on the game and players, are initialized in
 * {@link #setOurPlayerData()}.
 *<P>
 * At the start of each player's turn, {@link #buildingPlan} and most other state fields are cleared
 * (search {@link #run()} for <tt>mesType == SOCMessage.TURN</tt>).
 * The plan for what to build next is decided in {@link SOCRobotDM#planStuff(int)}
 * (called from {@link #planBuilding()} and some other places) which updates {@link #buildingPlan}.
 * That plan is executed in {@link #planAndDoActionForPLAY1()}, which calls {@link #buildOrGetResourceByTradeOrCard()}
 * and other strategy/decision methods.
 *<P>
 * Current status and the next expected action are tracked by the "waitingFor" and "expect" flag fields.
 * If we've sent the server an action and we're waiting for the result, {@link #waitingForGameState} is true
 * along with one other "expect" flag, such as {@link #expectPLACING_ROBBER}.
 * All these fields can be output for inspection by calling {@link #debugPrintBrainStatus()}.
 *<P>
 * See {@link #run()} for more details of how the bot waits for and reacts to incoming messages.
 * Some reactions are chosen in methods like {@link #considerOffer(SOCTradeOffer)} called from {@code run()}.
 * Some robot actions wait for other players or other timeouts; the brain counts {@link SOCTimingPing} messages
 * (1 per second) for timing.  For robustness testing, the {@code SOCRobotClient.debugRandomPause} flag can
 * be used to inject random delays in incoming messages.
 *<P>
 * To keep the game moving, the server may force an inactive bot to end its turn;
 * see {@link soc.server.SOCForceEndTurnThread}.
 *
 *<H3>AI/Robot development:</H3>
 *
 * The bot can be sent debug commands to examine its state; see
 * {@link SOCRobotClient#handleGAMETEXTMSG(soc.message.SOCGameTextMsg)}.
 *<P>
 * Extending this class is one way to begin developing a custom JSettlers bot:
 *<UL>
 * <LI> Factory is {@link SOCRobotClient#createBrain(SOCRobotParameters, SOCGame, CappedQueue)},
 *      which can be overridden in a custom bot client like {@link soc.robot.sample3p.Sample3PClient}
 * <LI> For a trivial example see {@link soc.robot.sample3p.Sample3PBrain}
 * <LI> For more complicated extensions, extend strategy classes and/or {@link SOCRobotDM},
 *      and override {@link #setStrategyFields()} and any other factory methods needed.
 * <LI> Game option {@link SOCGameOptionSet#K__EXT_BOT}, which can be set at server startup using the command line
 *      or {@code jsserver.properties} file, can be used to send custom data or config from server to third-party bots
 *</UL>
 * For other methods/stubs/callbacks which can be extended, browse this package for {@code protected} methods.
 * See {@code Readme.developer.md} for more about bot development.
 *
 * @author Robert S Thomas
 */
public abstract class SOCRobotBrain<DM extends SOCRobotDM<BP>, N extends SOCRobotNegotiator<BP>, BP extends SOCBuildPlan> extends Thread
{
    // Tuning parameters:

    /**
     * Bot pause speed-up factor when {@link SOCGame#isBotsOnly} in {@link #pause(int)}.
     * Default 0.25 (use 25% of normal pause time: 4x speed-up).
     * Use .01 for a shorter delay (1% of normal pauses).
     * @since 2.0.00
     */
    public static float BOTS_ONLY_FAST_PAUSE_FACTOR = .25f;

    /**
     * If, during a turn, we make this many illegal build
     * requests that the server denies, stop trying.
     * 
     * @see #failedBuildingAttempts
     * @since 1.1.00
     */
    public static int MAX_DENIED_BUILDING_PER_TURN = 3;

    /**
     * When a trade has been offered to humans (and maybe also to bots), pause
     * for this many seconds before accepting an offer to give humans a chance
     * to compete against fast bot decisions.
     *
     * @since 2.4.50
     */
    public static int BOTS_PAUSE_FOR_HUMAN_TRADE = 8;

    // Timing constants:

    /**
     * When a trade has been offered to humans (and maybe also to bots),
     * maximum wait in seconds for responses: {@link #tradeResponseTimeoutSec}.
     * Longer than {@link #TRADE_RESPONSE_TIMEOUT_SEC_BOTS_ONLY}.
     *<P>
     * Before v2.3.00 this was 100 seconds, which felt glacially slow
     * compared to the quick pace of most bot activity.
     *
     * @since 2.0.00
     */
    protected static final int TRADE_RESPONSE_TIMEOUT_SEC_HUMANS = 30;

    /**
     * When a trade has been offered to only bots (not to any humans),
     * maximum wait in seconds for responses: {@link #tradeResponseTimeoutSec}.
     * Shorter than {@link #TRADE_RESPONSE_TIMEOUT_SEC_HUMANS}.
     * @since 2.0.00
     */
    protected static final int TRADE_RESPONSE_TIMEOUT_SEC_BOTS_ONLY = 5;

	int[] stateVector;
	/**
	 * flag for allowing only one save per brain
	 */
	public boolean saved = false;
	
    /**
     * The robot parameters. See {@link #getRobotParameters()} for details.
     * @see SOCRobotClient#currentRobotParameters
     */
    protected SOCRobotParameters robotParameters;

    /**
     * Random number generator
     */
    protected Random rand = new Random();

    /**
     * The client we are hooked up to
     * TODO: This should really be private, logic shouldn't depend on this.  Debugging does, though
     */
    protected SOCRobotClient client;

    /**
     * Dummy player for cancelling bad placements
     * @since 1.1.00
     */
    protected SOCPlayer dummyCancelPlayerData;

    /**
     * The queue of game messages; contents are {@link SOCMessage}.
     */
    protected CappedQueue<SOCMessage> gameEventQ;

    /**
     * The game messages received this turn / previous turn, for debugging.
     * @since 1.1.13
     */
    protected Vector<SOCMessage> turnEventsCurrent, turnEventsPrev;

    /**
     * Number of exceptions caught this turn, if any.
     * Resets at each player's turn during {@link SOCMessage#TURN TURN} message.
     * @since 1.1.20
     */
    protected int turnExceptionCount;

    /**
     * the thing that determines what we want to build next
     */
    protected DM decisionMaker;

    /**
     * The data and code that determines how we negotiate.
     * {@link SOCRobotNegotiator#setTargetPiece(int, SOCPossiblePiece)}
     * is set when {@link #buildingPlan} is updated.
     * @see #tradeWithBank(SOCBuildPlan)
     * @see #makeOffer(SOCBuildPlan)
     * @see #considerOffer(SOCTradeOffer)
     * @see #tradeStopWaitingClearOffer()
     */
    protected N negotiator;

    /**
     * Our {@link SOCBuildingSpeedEstimate} factory.
     * @see #getEstimatorFactory()
     * @since 2.4.50
     */
    protected SOCBuildingSpeedEstimateFactory bseFactory;
    
    /**
     * a thread that sends ping messages to this one
     */
    protected SOCRobotPinger pinger;

    /**
     * An object for recording a building plan's debug information that can
     * be accessed interactively.
     * See {@link #getDRecorder()} and debug commands in
     * {@link SOCRobotClient#handleGAMETEXTMSG_debug}.
     */
    private DebugRecorder[] dRecorder;

    /**
     * keeps track of which dRecorder is current.
     * When the bot starts a new building plan, it switches dRecorders.
     */
    private int currentDRecorder; 
      
    /**
     * Our player tracker within {@link #playerTrackers}.
     */
    protected SOCPlayerTracker ourPlayerTracker;

    /**
     * Trackers for all players (one per player number, including this robot).
     * Null until {@link #setOurPlayerData()}; see {@link #addPlayerTracker(int)} for lifecycle info.
     * Elements for vacant seats are {@code null}.
     *<P>
     * Before v2.3.00 this was a {@link HashMap}.
     * Converted to array to avoid iterator ConcurrentModificationExceptions
     * during {@code *LOADGAME*} debug command.
     *
     * @see #ourPlayerTracker
     */
    protected SOCPlayerTracker[] playerTrackers;

    /**
     * This is our current building plan.
     *<P>
     * Cleared at the start of each player's turn, and a few other places
     * if certain conditions arise, by calling {@link #resetBuildingPlan()}.
     * Set in {@link #planBuilding()}.
     * When making a {@link #buildingPlan}, be sure to also set
     * {@link #negotiator}'s target piece.
     *<P>
     * {@link SOCRobotDM#buildingPlan} is the same Stack.
     *
     * @see #whatWeWantToBuild
     */
    protected BP buildingPlan;

    /**
     * The game we are playing. Set in constructor, unlike {@link #ourPlayerData}.
     * @see #gameIs6Player
     */
    protected SOCGame game;
    
    /**
     * Our player data.
     * Set in {@link #setOurPlayerData()}
     * @see #ourPlayerNumber
     * @see #ourPlayerName
     * @see #game
     */
    protected SOCPlayer ourPlayerData;  

    /**
     * Our player number; set in {@link #setOurPlayerData()}.
     * @since 2.0.00
     */
    protected int ourPlayerNumber;

    /**
     * Our player nickname. Convenience field, set from
     * {@link SOCDisplaylessPlayerClient#getNickname() client.getNickname()}.
     * @since 2.0.00
     */
    protected final String ourPlayerName;

////Fields for controlling the "finite state machine" in the run loop //////   

    /**
     * If true, the {@link #game} we're playing is on the 6-player board.
     * @since 1.1.08
     */
    final protected boolean gameIs6Player;

    /**
     * A counter used to measure passage of time.
     * Incremented each second, when the server sends {@link SOCTimingPing}.
     * When we decide to take an action, resets to 0.
     * If counter gets too high, we assume a bug and leave the game (<tt>{@link #alive} = false</tt>).
     */
    protected int counter;

    /**
     * During this turn, which is another player's turn,
     * have we yet decided whether to do the Special Building phase
     * (for the 6-player board)?
     * @since 1.1.08
     */
    protected boolean decidedIfSpecialBuild;

    /**
     * true when we're waiting for our requested Special Building phase
     * (for the 6-player board).
     * @since 1.1.08
     */
    protected boolean waitingForSpecialBuild;

    /**
     * This is the piece we want to build now.
     * Set in {@link #buildOrGetResourceByTradeOrCard()} from {@link #buildingPlan},
     * used in {@link #placeIfExpectPlacing()}.
     * @see #whatWeFailedToBuild
     */
    protected SOCPlayingPiece whatWeWantToBuild;

    /**
     * This is what we tried building this turn,
     * but the server said it was an illegal move
     * (due to a bug in our robot).
     *
     * @see #whatWeWantToBuild
     * @see #failedBuildingAttempts
     * @since 1.1.00
     */
    protected SOCPlayingPiece whatWeFailedToBuild;

    /**
     * Track how many illegal placement requests we've
     * made this turn.  Avoid infinite turn length, by
     * preventing robot from alternately choosing two
     * wrong things when the server denies a bad build.
     *
     * @see #whatWeFailedToBuild
     * @see #MAX_DENIED_BUILDING_PER_TURN
     * @since 1.1.00
     */
    protected int failedBuildingAttempts;
    
    /**
     * flag to check if the brain has received load/save msg
     */
    protected boolean suspended = false;
    
    /**
     * Flag for whether or not we're alive.
     * From other threads, set false by calling {@link #kill()}.
     */
    protected volatile boolean alive;

    /**
     * Flag for whether or not it is our turn.
     * Updated near top of per-message loop in {@code run()}
     * based on {@link SOCGame#getCurrentPlayerNumber()}.
     */
    protected boolean ourTurn;
    
    /**
     * {@link #pause(int) Pause} for less time;
     * speeds up response in 6-player games.
     * Ignored if {@link SOCGame#isBotsOnly}, which pauses for even less time.
     * @since 1.1.09
     */
    private boolean pauseFaster;

    // If any new expect or waitingFor fields are added,
    // please update debugPrintBrainStatus() and the
    // run() loop at "if (mesType == SOCMessage.TURN)".

    /**
     * true if we're expecting the START1A state
     */
    protected boolean expectSTART1A;

    /**
     * true if we're expecting the START1B state
     */
    protected boolean expectSTART1B;

    /**
     * true if we're expecting the START2A state
     */
    protected boolean expectSTART2A;

    /**
     * true if we're expecting the START2B state
     */
    protected boolean expectSTART2B;

    /**
     * true if we're expecting the {@link SOCGame#START3A START3A} state.
     * @since 2.0.00
     */
    protected boolean expectSTART3A;

    /**
     * true if we're expecting the {@link SOCGame#START3B START3B} state.
     * @since 2.0.00
     */
    protected boolean expectSTART3B;

    /**
     * true if we're expecting the {@link SOCGame#ROLL_OR_CARD ROLL_OR_CARD} state.
     *<P>
     * Before v2.0.00 this field was {@code expectPLAY} because that state was named {@code PLAY}.
     */
    protected boolean expectROLL_OR_CARD;

    /**
     * true if we're expecting the {@link SOCGame#PLAY1 PLAY1} state
     */
    protected boolean expectPLAY1;

    /**
     * true if we're expecting the PLACING_ROAD state
     */
    protected boolean expectPLACING_ROAD;

    /**
     * true if we're expecting the PLACING_SETTLEMENT state
     */
    protected boolean expectPLACING_SETTLEMENT;

    /**
     * true if we're expecting the PLACING_CITY state
     */
    protected boolean expectPLACING_CITY;

    /**
     * true if we're expecting the PLACING_SHIP game state
     * @since 2.0.00
     */
    protected boolean expectPLACING_SHIP;

    /**
     * true if we're expecting the PLACING_ROBBER state.
     * {@link #playKnightCard()} sets this field and {@link #waitingForGameState}.
     *<P>
     * In scenario {@link SOCGameOptionSet#K_SC_PIRI SC_PIRI}, this flag is also used when we've just played
     * a "Convert to Warship" card (Knight/Soldier card) and we're waiting for the
     * server response.  The response won't be a GAMESTATE(PLACING_SOLDIER) message,
     * it will either be PLAYERLEMENT(GAIN, SCENARIO_WARSHIP_COUNT) or DEVCARDACTION(CANNOT_PLAY).
     * Since this situation is otherwise the same as playing a Knight/Soldier, we use
     * this same waiting flags.
     */
    protected boolean expectPLACING_ROBBER;

    /**
     * true if we're expecting the PLACING_FREE_ROAD1 state
     */
    protected boolean expectPLACING_FREE_ROAD1;

    /**
     * true if we're expecting the PLACING_FREE_ROAD2 state
     */
    protected boolean expectPLACING_FREE_ROAD2;

    /**
     * true if we're expecting the {@link SOCGame#PLACING_INV_ITEM PLACING_INV_ITEM} state.
     * @since 2.0.00
     */
    protected boolean expectPLACING_INV_ITEM;

    /**
     * true if were expecting a PUTPIECE message after
     * a START1A game state
     */
    protected boolean expectPUTPIECE_FROM_START1A;

    /**
     * true if were expecting a PUTPIECE message after
     * a START1B game state
     */
    protected boolean expectPUTPIECE_FROM_START1B;

    /**
     * true if were expecting a PUTPIECE message after
     * a START1A game state
     */
    protected boolean expectPUTPIECE_FROM_START2A;

    /**
     * true if were expecting a PUTPIECE message after
     * a START1A game state
     */
    protected boolean expectPUTPIECE_FROM_START2B;

    /**
     * true if were expecting a PUTPIECE message after
     * a {@link SOCGame#START3A START3A} game state.
     * @since 2.0.00
     */
    protected boolean expectPUTPIECE_FROM_START3A;

    /**
     * true if were expecting a PUTPIECE message after
     * a {@link SOCGame#START3B START3B} game state.
     * @since 2.0.00
     */
    protected boolean expectPUTPIECE_FROM_START3B;

    /**
     * true if we're expecting a DICERESULT message
     */
    protected boolean expectDICERESULT;

    /**
     * true if we're expecting a DISCARDREQUEST message
     */
    protected boolean expectDISCARD;

    /**
     * true if we're expecting to have to move the robber
     */
    protected boolean expectMOVEROBBER;

    /**
     * true if we're expecting to pick two resources
     */
    protected boolean expectWAITING_FOR_DISCOVERY;

    /**
     * True if we're expecting to pick a monopoly.
     * When game state {@link SOCGame#WAITING_FOR_MONOPOLY} arrives,
     * will send a resource type and set {@link #expectPLAY1}.
     */
    protected boolean expectWAITING_FOR_MONOPOLY;

    // If any new expect or waitingFor fields are added,
    // please update debugPrintBrainStatus() and maybe also
    // the section of run() at (mesType == SOCMessage.TURN).

    /**
     * true if we're waiting for a GAMESTATE message from the server.
     * This is set after a robot action or requested action is sent to server,
     * or just before ending our turn (which also sets {@link #waitingForOurTurn} == true).
     *<P>
     * For example, when playing a {@link SOCDevCardAction}, set true and also set
     * an "expect" flag ({@link #expectPLACING_ROBBER}, {@link #expectWAITING_FOR_DISCOVERY}, etc).
     *<P>
     * <b>Special case:</b><br>
     * In scenario {@link SOCGameOptionSet#K_SC_PIRI SC_PIRI}, this flag is also set when we've just played
     * a "Convert to Warship" card (Knight/Soldier card), although the server won't
     * respond with a GAMESTATE message; see {@link #expectPLACING_ROBBER} javadoc.
     *
     * @see #rejectedPlayDevCardType
     * @see #rejectedPlayInvItem
     */
    protected boolean waitingForGameState;

    /**
     * true if we're waiting for a {@link SOCTurn TURN} message from the server
     * when it's our turn
     * @see #waitingForTurnMain
     */
    protected boolean waitingForOurTurn;

    /**
     * True if it's a new turn and game state is or was recently {@link SOCGame#ROLL_OR_CARD},
     * not yet {@link SOCGame#PLAY1}. When this flag is true and state becomes {@code PLAY1},
     * brain will set it false and call {@link #startTurnMainActions()}.
     * @see #waitingForOurTurn
     * @since 2.4.50
     */
    private boolean waitingForTurnMain;

    /**
     * true when we're waiting for the results of our requested bank trade.
     * @see #waitingForTradeResponse
     */
    protected boolean waitingForTradeMsg;

    /**
     * true when we're waiting to receive a dev card
     */
    protected boolean waitingForDevCard;

    /**
     * True when the robber will move because a seven was rolled.
     * Used to help bot remember why the robber is moving (Knight dev card, or 7).
     * Set true when {@link SOCMessage#DICERESULT} received.
     * Read in gamestate {@link SOCGame#PLACING_ROBBER PLACING_ROBBER}.
     */
    public boolean moveRobberOnSeven;

    /**
     * True if we're waiting for a player response to our offered trade message.
     * Max wait time is {@link #tradeResponseTimeoutSec}.
     * @see #makeOffer(SOCBuildPlan)
     * @see #doneTrading
     * @see #waitingForTradeMsg
     */
    protected boolean waitingForTradeResponse;

    /**
     * When {@link #waitingForTradeResponse}, how many seconds to wait
     * before we stop waiting for response to a trade message.
     * Longer if trade is offered to humans, shorter if bots only:
     * {@link #TRADE_RESPONSE_TIMEOUT_SEC_HUMANS}, {@link #TRADE_RESPONSE_TIMEOUT_SEC_BOTS_ONLY}.
     * Updated when {@link #waitingForTradeResponse} is set true.
     * @since 2.0.00
     */
    protected int tradeResponseTimeoutSec;

    /**
     * Non-{@code null} if we're waiting for server response to picking
     * a {@link SOCSpecialItem}, for certain scenarios; contains the {@code typeKey}
     * of the special item we're waiting on.
     */
    protected String waitingForPickSpecialItem;

    /**
     * True if we're in a {@link SOCGameOptionSet#K_SC_PIRI _SC_PIRI} game
     * and waiting for server response to a {@link SOCSimpleRequest}
     * to attack a pirate fortress.
     */
    protected boolean waitingForSC_PIRI_FortressRequest;

    // If any new expect or waitingFor fields are added,
    // please update debugPrintBrainStatus().

    /**
     * true if we're done trading
     * @see #makeOffer(SOCBuildPlan)
     * @see #waitingForTradeResponse
     */
    protected boolean doneTrading;

    /**
     * true if we are waiting for a trade response from a given player.  if so, we cannot
     *  proceed to further trade offers to that player or other actions, in order to avoid
     *  synchronization conflicts.
     */
    protected boolean[] waitingForTradeResponsePlayer; 
    
    /**
     * true if our most recent trade offer was accepted
     */
    protected boolean tradeAccepted;

    /**
     * If set, the server rejected our play of this dev card type this turn
     * (such as {@link SOCDevCardConstants#KNIGHT}) because of a bug in our
     * robot; should not attempt to play the same type again this turn.
     * Otherwise -1.
     * @since 1.1.17
     */
    protected int rejectedPlayDevCardType;

    /**
     * If not {@code null}, the server rejected our play of this {@link SOCInventoryItem}
     * this turn, probably because of a bug in our robot. Should not attempt to
     * play an item of the same {@link SOCInventoryItem#itype itype} again this turn.
     * @since 2.0.00
     */
    protected SOCInventoryItem rejectedPlayInvItem;
        // TODO refine later: must build/play something else first, have that clear this field. After building/playing
        // something else, the previously rejected inv item type might be okay to play again this turn.
        // Don't need to also add a count of play inv item rejections this turn (to avoid loop forever
        // asking & being rejected between building other things) because would run out of other things.
        // To find places which build/play something else, look for counter = 0.

    /**
     * the game state before the current one
     */
    protected int oldGameState;

    /**
     * During START states, coordinate of our most recently placed road or settlement.
     * Used to avoid repeats in {@link #cancelWrongPiecePlacement(SOCCancelBuildRequest)}.
     * @since 1.1.09
     */
    protected int lastStartingPieceCoord;

    /**
     * During START1B and START2B states, coordinate of the potential settlement node
     * towards which we're building, as calculated by {@link OpeningBuildStrategy#planInitRoad()}.
     * Used to avoid repeats in {@link #cancelWrongPiecePlacementLocal(SOCPlayingPiece)}.
     * @since 1.1.09
     */
    protected int lastStartingRoadTowardsNode;

    /**
     * Strategy to choose discards.
     * @since 2.2.00
     */
    protected DiscardStrategy discardStrategy;

    /**
     * Strategy to plan and build initial settlements and roads.
     * @since 2.0.00
     */
    protected OpeningBuildStrategy openingBuildStrategy;

    /**
     * Strategy to choose whether to monopolize, and which resource.
     * @since 2.0.00
     */
    protected MonopolyStrategy monopolyStrategy;

    /**
     * Strategy to rob players.
     * @since 2.2.00
     */
    protected RobberStrategy robberStrategy;

    /**
     * keeps track of the last thing we bought, for debugging purposes
     */
    protected SOCPossiblePiece lastMove;

    /**
     * keeps track of the last thing we wanted, for debugging purposes
     */
    protected SOCPossiblePiece lastTarget;
    
    /**
     * A static variable to control the amount of time to pause at certain points (eg after placing a settlement).  This is done for the benefit of human players, but 
     * may not be required if we are playing robot vs. robot.  Likewise, it may need to be extended for human players - 1.5s isn't a lot of time, and actions are easy
     * to overlook.
     * The default is 500.  However, this is often multiplied by a factor in the code.  
     * The exception for this is when waiting for a trade offer - in that case, a longer delay should occur, whether or not there is a human player.  In the case
     * of partial offers, it may take a second or two to assess.
     * 
     * TODO: This should be refactored properly, but this is a good start for now (architecture makes it tough to do things right!)
     * 
     */
    private static int delayTime = 500;
    public static void setDelayTime(int t) {
    	delayTime = t;
    }
    public static int getDelayTime(){
    	return delayTime;
    }
    
    /**
     * Static variable to turn off trading.  This should be handled in parameters, but the way those are passed is awful as it is, so it's not worth the effort.
     * Trading is the slowest aspect of a robots-only game, as the manner in which robots wait to hear back on trades is broken TODO: Fix trade reply waiting logic.
     * Turning off trades allows us to simulate games more quickly in order to do basic strategy comparison.
     * 
     */
    private static boolean disableTrades = false;
    public static void setTradesEnabled(boolean t) {
    	disableTrades = !t;
    }
    
    /**
     * We're using the number of messages the robot receives as a crude way to keep track of 
     * the time that has elapsed in the current game.
     */
    protected int numberOfMessagesReceived = 0;
    
    /**
     * Create a robot brain to play a game.
     *<P>
     * Depending on {@link SOCGame#getGameOptions() game options},
     * constructor might copy and alter the robot parameters
     * (for example, to clear {@link SOCRobotParameters#getTradeFlag()}).
     *<P>
     * Please call {@link #setOurPlayerData()} before using this brain or starting its thread.
     *
     * @param rc  the robot client
     * @param params  the robot parameters
     * @param ga  the game we're playing
     * @param mq  the message queue
     */
    public SOCRobotBrain(SOCRobotClient rc, SOCRobotParameters params, SOCGame ga, CappedQueue<SOCMessage> mq)
    {
        client = rc;
        ourPlayerName = rc.getNickname();
        robotParameters = params.copyIfOptionChanged(ga.getGameOptions());
        game = ga;
        gameIs6Player = (ga.maxPlayers > 4);
        pauseFaster = gameIs6Player;
        gameEventQ = mq;
        turnEventsCurrent = new Vector<SOCMessage>();
        turnEventsPrev = new Vector<SOCMessage>();
        numberOfMessagesReceived = 0;
        alive = true;
        counter = 0;

        expectSTART1A = true;
        expectSTART1B = false;
        expectSTART2A = false;
        expectSTART2B = false;
        expectROLL_OR_CARD = false;
        expectPLAY1 = false;
        expectPLACING_ROAD = false;
        expectPLACING_SETTLEMENT = false;
        expectPLACING_CITY = false;
        expectPLACING_SHIP = false;
        expectPLACING_ROBBER = false;
        expectPLACING_FREE_ROAD1 = false;
        expectPLACING_FREE_ROAD2 = false;
        expectPUTPIECE_FROM_START1A = false;
        expectPUTPIECE_FROM_START1B = false;
        expectPUTPIECE_FROM_START2A = false;
        expectPUTPIECE_FROM_START2B = false;
        expectDICERESULT = false;
        expectDISCARD = false;
        expectMOVEROBBER = false;
        expectWAITING_FOR_DISCOVERY = false;
        expectWAITING_FOR_MONOPOLY = false;

        ourTurn = false;
        oldGameState = game.getGameState();
        waitingForGameState = false;
        waitingForOurTurn = false;
        waitingForTradeMsg = false;
        waitingForDevCard = false;
        waitingForSpecialBuild = false;
        decidedIfSpecialBuild = false;
        moveRobberOnSeven = false;
        waitingForTradeResponse = false;
        doneTrading = false;
        waitingForTradeResponsePlayer = new boolean[game.maxPlayers];
        for (int i = 0; i < game.maxPlayers; i++)
        {
            waitingForTradeResponsePlayer[i] = false;
        }

        buildingPlan = createBuildPlan();
        pinger = new SOCRobotPinger(gameEventQ, game.getName(), client.getNickname() + "-" + game.getName());
        dRecorder = new DebugRecorder[2];
        dRecorder[0] = new DebugRecorder();
        dRecorder[1] = new DebugRecorder();
        currentDRecorder = 0;

        // Strategy fields will be set in setOurPlayerData();
        // we don't have the data yet.
    }

    /**
     * 
     * @return true if brain suspended, false otherwise
     */
    public boolean isSuspended(){
    	return suspended;
    }

    /**
     * Get this bot's parameters, as set in constructor.
     *
     * @return the robot parameters
     */
    public SOCRobotParameters getRobotParameters()
    {
        return robotParameters;
    }

    /**
     * @return the player client
     */
    public SOCRobotClient getClient()
    {
        return client;
    }

   

    /**
     * A player has sat down and been added to the game,
     * during game formation. Create a PlayerTracker for them.
     *<p>
     * Called when SITDOWN received from server; one SITDOWN is
     * sent for every player, and our robot player might not be the
     * first or last SITDOWN.
     *<p>
     * Since our playerTrackers are initialized when our robot's
     * SITDOWN is received (robotclient calls {@link #setOurPlayerData()}),
     * and seats may be vacant at that time (because SITDOWN not yet
     * received for those seats), we must add a PlayerTracker for
     * each SITDOWN received after our player's.
     *
     * @param pn Player number
     * @since 1.1.00
     */
    public void addPlayerTracker(int pn)
    {
        if (null == playerTrackers)
        {
            // SITDOWN hasn't been sent for our own player yet.
            // When it is, playerTrackers will be initialized for
            // each non-vacant player, including pn.

            return;
        }

        if (null == playerTrackers[pn])
            playerTrackers[pn] = new SOCPlayerTracker(game.getPlayer(pn), this);
    }

    /**
     * @return the decision maker
     */
    public DM getDecisionMaker()
    {
        return decisionMaker;
    }

    /**
     * turns the debug recorders on
     * @see #getDRecorder()
     * @see #turnOffDRecorder()
     */
    public void turnOnDRecorder()
    {
        dRecorder[0].turnOn();
        dRecorder[1].turnOn();
    }

    /**
     * turns the debug recorders off
     * @see #turnOnDRecorder()
     */
    public void turnOffDRecorder()
    {
        dRecorder[0].turnOff();
        dRecorder[1].turnOff();
    }

    /**
     * Get this bot's current Debug Recorder data.
     * The Debug Recorder is an object for recording a building plan's debug information that can
     * be accessed interactively.
     * @return the debug recorder
     * @see #getOldDRecorder()
     * @see #turnOnDRecorder()
     */
    public DebugRecorder getDRecorder()
    {
        return dRecorder[currentDRecorder];
    }

    /**
     * Get this bot's Debug Recorder data for the previously built piece.
     * @return the old debug recorder
     * @see #getDRecorder()
     */
    public DebugRecorder getOldDRecorder()
    {
        return dRecorder[(currentDRecorder + 1) % 2];
    }

    /**
     * @return the last move we made
     */
    public SOCPossiblePiece getLastMove()
    {
        return lastMove;
    }

    /**
     * @return our last target piece
     */
    public SOCPossiblePiece getLastTarget()
    {
        return lastTarget;
    }

    /**
     * When we join a game and sit down to begin play,
     * find our player data using our nickname.
     * Called from {@link SOCRobotClient} when the
     * server sends a {@link SOCSitDown} message.
     *<P>
     * Initializes our game and {@link #ourPlayerData}, {@link SOCPlayerTracker}s, etc.
     * Calls {@link #setStrategyFields()} to set {@link SOCRobotDM}, {@link SOCRobotNegotiator},
     * {@link RobberStrategy}, and other strategy fields,
     */
    public void setOurPlayerData()
    {
        ourPlayerData = game.getPlayer(client.getNickname());
        ourPlayerTracker = new SOCPlayerTracker(ourPlayerData, this);
        ourPlayerNumber = ourPlayerData.getPlayerNumber();
        playerTrackers = new SOCPlayerTracker[game.maxPlayers];
        playerTrackers[ourPlayerNumber] = ourPlayerTracker;

        for (int pn = 0; pn < game.maxPlayers; pn++)
        {
            if ((pn != ourPlayerNumber) && ! game.isSeatVacant(pn))
            {
                SOCPlayerTracker tracker = new SOCPlayerTracker(game.getPlayer(pn), this);
                playerTrackers[pn] = tracker;
            }
        }

        setStrategyFields();

        dummyCancelPlayerData = new SOCPlayer(-2, game);

        // Verify expected face (fast or smart robot)
        int faceId;
        switch (getRobotParameters().getStrategyType())
        {
        case SOCRobotDMImpl.SMART_STRATEGY:
            faceId = -1;  // smarter robot face
            break;

        default:
            faceId = 0;   // default robot face
        }
        
        Random r = new Random();
        faceId = r.nextInt(74) - 1; //allow the smarter robot face also
        
        if (ourPlayerData.getFaceId() != faceId)
        {
            ourPlayerData.setFaceId(faceId);
            // robotclient will handle sending it to server
        }
    }

    /**
     * Sets the SOCPlayer object that represents our player data
     * @param pl
     */
    public void setOurPlayerData(SOCPlayer pl){
    	ourPlayerData = pl;
    }
    
    /**
     * Make the bot strategy selections, as part of getting ready to sit and play
     * in {@link #setOurPlayerData()}. Fields like {@link #game}, {@link #ourPlayerData},
     * and {@link #playerTrackers} are set before calling this method.
     *<P>
     * Selections or behavior within strategy classes may be influenced by
     * {@link #getRobotParameters()}.{@link SOCRobotParameters#getStrategyType() getStrategyType()}.
     *<P>
     * Fields set here:
     *<UL>
     * <LI> {@link #decisionMaker}: calls {@link #createDM()}
     * <LI> {@link #negotiator}: calls {@link #createNegotiator()}
     * <LI> {@link #bseFactory}: calls {@link #createEstimatorFactory()}
     * <LI> {@link #discardStrategy}
     * <LI> {@link #monopolyStrategy}
     * <LI> {@link #openingBuildStrategy}
     * <LI> {@link #robberStrategy}
     *</UL>
     * When overriding this class: You may either set all those fields yourself,
     * or call {@code super.setStrategyFields()} and then change the ones you need customized.
     *
     * @since 2.2.00
     */
    protected void setStrategyFields()
    {
        decisionMaker = createDM();
        negotiator = createNegotiator();
        bseFactory = createEstimatorFactory();
        discardStrategy = new DiscardStrategy(game, ourPlayerData, this, rand);
        monopolyStrategy = new MonopolyStrategy(game, ourPlayerData, this);
        openingBuildStrategy = new OpeningBuildStrategy(game, ourPlayerData, this);
        robberStrategy = new RobberStrategy(game, ourPlayerData, this, rand);
    }

    /**
     * Get the number of messages the robot has received, which we are using
     * as a crude way to track the time that has elapsed in this game.
     * @author Markus Guhe
     */
    public int getNumberOfMesagesReceived() {
        return numberOfMessagesReceived;
    }
    
    /**
     * Print brain variables and status for this game to a list of {@link String}s.
     * Includes all of the expect and waitingFor fields (<tt>expectROLL_OR_CARD</tt>,
     * <tt>waitingForGameState</tt>, etc.)
     * Also prints the game state, and the messages received by this brain
     * during the previous and current turns.
     *<P>
     * Before v1.1.20, this printed to {@link System#err} instead of returning the status as Strings.
     * @since 1.1.13
     */
    public List<String> debugPrintBrainStatus()
    {
        ArrayList<String> rbSta = new ArrayList<String>();

        if ((ourPlayerData == null) || (game == null))
        {
            rbSta.add("Robot internal state: Cannot print: null game or player");
            return rbSta;
        }

        rbSta.add("Robot internal state: "
                + ((client != null) ? client.getNickname() : ourPlayerData.getName())
                + " in game " + game.getName()
                + ": gs=" + game.getGameState());
        if (waitingForPickSpecialItem != null)
            rbSta.add("  waitingForPickSpecialItem = " + waitingForPickSpecialItem);
        if (game.getGameState() == SOCGame.WAITING_FOR_DISCARDS)
            rbSta.add("  bot resource count = " + ourPlayerData.getResources().getTotal());
        if (rejectedPlayDevCardType != -1)
            rbSta.add("  rejectedPlayDevCardType = " + rejectedPlayDevCardType);
        if (rejectedPlayInvItem != null)
            rbSta.add("  rejectedPlayInvItem = " + rejectedPlayInvItem);

        // Reminder: Add new state fields to both s[] and b[]

        final String[] s = {
            "ourTurn", "doneTrading",
            "waitingForGameState", "waitingForOurTurn", "waitingForTurnMain", "waitingForTradeMsg", "waitingForDevCard",
            "waitingForTradeResponse", "waitingForSC_PIRI_FortressRequest",
            "moveRobberOnSeven", "expectSTART1A", "expectSTART1B", "expectSTART2A", "expectSTART2B", "expectSTART3A", "expectSTART3B",
            "expectROLL_OR_CARD", "expectPLAY1", "expectPLACING_ROAD", "expectPLACING_SETTLEMENT", "expectPLACING_CITY", "expectPLACING_SHIP",
            "expectPLACING_ROBBER", "expectPLACING_FREE_ROAD1", "expectPLACING_FREE_ROAD2", "expectPLACING_INV_ITEM",
            "expectPUTPIECE_FROM_START1A", "expectPUTPIECE_FROM_START1B", "expectPUTPIECE_FROM_START2A", "expectPUTPIECE_FROM_START2B",
            "expectPUTPIECE_FROM_START3A", "expectPUTPIECE_FROM_START3B",
            "expectDICERESULT", "expectDISCARD", "expectMOVEROBBER", "expectWAITING_FOR_DISCOVERY", "expectWAITING_FOR_MONOPOLY"
        };
        final boolean[] b = {
            ourTurn, doneTrading,
            waitingForGameState, waitingForOurTurn, waitingForTurnMain, waitingForTradeMsg, waitingForDevCard,
            waitingForTradeResponse, waitingForSC_PIRI_FortressRequest,
            moveRobberOnSeven, expectSTART1A, expectSTART1B, expectSTART2A, expectSTART2B, expectSTART3A, expectSTART3B,
            expectROLL_OR_CARD, expectPLAY1, expectPLACING_ROAD, expectPLACING_SETTLEMENT, expectPLACING_CITY, expectPLACING_SHIP,
            expectPLACING_ROBBER, expectPLACING_FREE_ROAD1, expectPLACING_FREE_ROAD2, expectPLACING_INV_ITEM,
            expectPUTPIECE_FROM_START1A, expectPUTPIECE_FROM_START1B, expectPUTPIECE_FROM_START2A, expectPUTPIECE_FROM_START2B,
            expectPUTPIECE_FROM_START3A, expectPUTPIECE_FROM_START3B,
            expectDICERESULT, expectDISCARD, expectMOVEROBBER, expectWAITING_FOR_DISCOVERY, expectWAITING_FOR_MONOPOLY
        };
        if (s.length != b.length)
        {
            rbSta.add("L745: Internal error: array length");
            return rbSta;
        }
        int slen = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length; ++i)
        {
            if ((slen + s[i].length() + 8) > 79)
            {
                rbSta.add(sb.toString());
                slen = 0;
                sb.delete(0, sb.length());
            }
            sb.append("  ");
            sb.append(s[i]);
            sb.append(": ");
            if (b[i])
                sb.append("TRUE");
            else
                sb.append("false");
            slen = sb.length();
        }
        if (slen > 0)
            rbSta.add(sb.toString());

        debugPrintTurnMessages(turnEventsPrev, "previous", rbSta);
        debugPrintTurnMessages(turnEventsCurrent, "current", rbSta);

        return rbSta;
    }

    /**
     * Add the contents of this Vector as Strings to the provided list.
     * One element per line, indented by <tt>\t</tt>.
     * Headed by a line formatted as one of:
     *<BR>  Current turn: No messages received.
     *<BR>  Current turn: 5 messages received:
     * @param msgV  Vector of {@link SOCMessage}s from server
     * @param msgDesc  Short description of the vector, like 'previous' or 'current'
     * @param toList  Add to this list
     * @since 1.1.13
     */
    protected static void debugPrintTurnMessages
        (List<?> msgV, final String msgDesc, List<String> toList)
    {
        final int n = msgV.size();
        if (n == 0)
        {
            toList.add("  " + msgDesc + " turn: No messages received.");
        } else {
            toList.add("  " + msgDesc + " turn: " + n + " messages received:");
            for (int i = 0; i < n; ++i)
                toList.add("\t" + msgV.get(i));
        }
    }

    /**
     * Here is the run method.  Just keep receiving game events
     * through {@link #gameEventQ} and deal with each one.
     * Remember that we're sent a {@link SOCTimingPing} event once per second,
     * incrementing {@link #counter}.  That allows the bot to wait a certain
     * time for other players before it decides whether to do something.
     *<P>
     * Nearly all bot actions start in this method; the overview of bot structures
     * is in the {@link SOCRobotBrain class javadoc} for prominence.
     * See comments within <tt>run()</tt> for minor details.
     *<P>
     * The brain thread will run until {@link #kill()} has been called or its pinger stops,
     * or it receives a {@link SOCMessage#ROBOTDISMISS} request to exit the game.
     */
    @Override
    public void run()
    {
        // Thread name for debug
        try
        {
            Thread.currentThread().setName("robotBrain-" + client.getNickname() + "-" + game.getName());
        }
        catch (Throwable th) {}

        if (pinger != null)
        {
            pinger.start();
            //
            // Along with actual game events, the pinger sends a TIMINGPING message
            // once per second, to aid the robot's timekeeping counter.
            //

            while (alive)
            {
                try
                {
                	if(Thread.currentThread().isInterrupted())
                		break; //exit the while loop so we can clean this thread
                	
                    final SOCMessage mes = gameEventQ.get();  // Sleeps until message received

                    final int mesType;
                    if (mes != null)
                    {
                        // Debug aid: When looking at message contents or setting a per-message breakpoint,
                        // skip the pings; note (mesType != SOCMessage.TIMINGPING) here.

                        mesType = mes.getType();
                        numberOfMessagesReceived++; // this is our crude internal clock
                        if (mesType != SOCMessage.TIMINGPING)
                            turnEventsCurrent.addElement(mes);
                        //if (D.ebugOn)
                        //    D.ebugPrintlnINFO("mes - " + mes); //---MG
                    }
                    else
                    {
                        mesType = -1;
                    }
                    
                    if(mesType==SOCMessage.COLLECTDATA){
                    	//check the brain is of the correct type and if the message is for us
                    	if(this.getClass().getName().equals(StacRobotBrain.class.getName()) && ((SOCCollectData) mes).getPlayerNumber() == ourPN){
                    		StacRobotBrain br = (StacRobotBrain) this;
                    		if(Simulation.dbh!= null && Simulation.dbh.isConnected()){// !!!
                    			//active game with at least one of our piece on the board and our turn
		                    	if(game.getGameState()>=SOCGame.START1A && game.getGameState()<=SOCGame.OVER 
		                    			&& game.getPlayer(ourPN).getPieces().size()>0 && game.getCurrentPlayerNumber()==ourPN){
		                    		//check if vector has changed since last computed to avoid duplicates
		                    		int[] vector = br.createStateVector();
		                    		if(stateVector==null || !Arrays.equals(stateVector, vector)){
		                    			Simulation.writeToDb(vector, br.calculateStateValue());
		                    			stateVector = vector;
		                    		}
		                    	}
	                    	}
                    	}
                    	continue;//do not go through all the logic below as this message does not influence the game (or should it be treated as a ping ?)
                    }
                    
                    //this should work as a brain suspend mechanism when loading/saving
                    if(mesType == SOCMessage.LOADGAME || mesType == SOCMessage.GAMECOPY){
                    	//wait for the loading/saving to finish before carrying on
                    	suspended = true;
                    	while(suspended){
                    		Thread.sleep(10);//sleep for a while
                    	}
                    	//now just follow the below logic as if the two msgs were just a simple ping msg
                    }
                    
                    if (waitingForTradeMsg && (counter > 10))
                    {
                    	/*
                    	 * NOTE: there is a weird timing issue with the two parameters for waiting trade response and waiting trade 
                    	 * confirmation as well as the waiting fields in the StacDialogue manager, therefore we only reset this field
                    	 * if the trades are via the old trade interface.
                    	 */
                    	if(this.getClass() != StacRobotBrain.class && !StacRobotBrain.isChatNegotiation()){
                    		waitingForTradeMsg = false;
                    	}

                        counter = 0;
                    }

                    if (waitingForTradeResponse && (counter > tradeResponseTimeoutSec))
                    {
                        // Remember other players' responses, call client.clearOffer,
                        // clear waitingForTradeResponse and counter.
                        
                    	tradeStopWaitingClearOffer();
                    }

                    if (waitingForGameState && (counter > 10000))
                    {
                        //D.ebugPrintln("counter = "+counter);
                        //D.ebugPrintln("RESEND");
                        counter = 0;
                        client.resend();
                    }

                    if (mesType == SOCMessage.GAMESTATE)
                    {
                        handleGAMESTATE(((SOCGameState) mes).getState());
                            // clears waitingForGameState, updates oldGameState, calls ga.setGameState
                            // May call startTurnMainActions
                            // If state is LOADING, sets waitingForGameState
                            // depending on type of robot, set to play1 either when you see play1 or play2...
                    }

                    else if (mesType == SOCMessage.STARTGAME)
                    {
                        SOCDisplaylessPlayerClient.handleSTARTGAME_checkIsBotsOnly(game);
                            // might set game.isBotsOnly
                        handleGAMESTATE(((SOCStartGame) mes).getGameState());
                            // clears waitingForGameState, updates oldGameState, calls ga.setGameState
                    }

                    else if (mesType == SOCMessage.TURN)
                    {
                        // Start of a new player's turn.
                        // Update game and reset most of our state fields.
                        // See also below: if ((mesType == SOCMessage.TURN) && ourTurn).

                        handleGAMESTATE(((SOCTurn) mes).getGameState());
                            // clears waitingForGameState, updates oldGameState, calls ga.setGameState

                        game.setCurrentPlayerNumber(((SOCTurn) mes).getPlayerNumber());
                        game.updateAtTurn();

                        //
                        // remove any expected states
                        //
                        expectROLL_OR_CARD = false;
                        expectPLAY1 = false;
                        expectPLACING_ROAD = false;
                        expectPLACING_SETTLEMENT = false;
                        expectPLACING_CITY = false;
                        expectPLACING_SHIP = false;
                        expectPLACING_ROBBER = false;
                        expectPLACING_FREE_ROAD1 = false;
                        expectPLACING_FREE_ROAD2 = false;
                        expectPLACING_INV_ITEM = false;
                        expectDICERESULT = false;
                        expectDISCARD = false;
                        expectMOVEROBBER = false;
                        expectWAITING_FOR_DISCOVERY = false;
                        expectWAITING_FOR_MONOPOLY = false;

                        //
                        // reset the selling flags and offers history
                        //
                        if (robotParameters.getTradeFlag() == 1)
                        {
                            doneTrading = false;
                        }
                        else
                        {
                            doneTrading = true;
                        }

                        waitingForTradeMsg = false;
                        waitingForTradeResponse = false;
                        for (int i=0; i<waitingForTradeResponsePlayer.length; i++) {
                            waitingForTradeResponsePlayer[i] = false;
                        }
                        negotiator.resetIsSelling();
                        negotiator.resetOffersMade();
                        negotiator.resetTradesMade();

                        waitingForPickSpecialItem = null;
                        waitingForSC_PIRI_FortressRequest = false;

                        //
                        // check or reset any special-building-phase decisions
                        //
                        decidedIfSpecialBuild = false;
                        if (game.getGameState() == SOCGame.SPECIAL_BUILDING)
                        {
                            if (waitingForSpecialBuild && ! getBuildingPlan().isEmpty())
                            {
                                // Keep the building plan.
                                // Will ask during loop body to build.
                            } else {
                                // We have no plan, but will call planBuilding()
                                // during the loop body.  If buildingPlan still empty,
                                // bottom of loop will end our Special Building turn,
                                // just as it would in gamestate PLAY1.  Otherwise,
                                // will ask to build after planBuilding.
                            }
                        } else {
                            //
                            // reset any plans we had
                            //
                        	resetBuildingPlan();
                        }
                        negotiator.resetTargetPieces();

                        //
                        // swap the message-history queues
                        //
                        {
                            Vector<SOCMessage> oldPrev = turnEventsPrev;
                            turnEventsPrev = turnEventsCurrent;
                            oldPrev.clear();
                            turnEventsCurrent = oldPrev;
                        }

                        turnExceptionCount = 0;
                    }
                    else if (mesType == SOCMessage.GAMESTATS)
                    {
                        handleGAMESTATS((SOCGameStats) mes);
                    }

                    if (game.getCurrentPlayerNumber() == ourPlayerNumber)
                    {
                        ourTurn = true;
                        waitingForSpecialBuild = false;
                    }
                    else
                    {
                        ourTurn = false;
                    }

                    if ((mesType == SOCMessage.TURN) && ourTurn)
                    {
                        //useful for debugging trade interactions
                        //if (ourPlayerData.getName().contains("partial") && ourTurn) {
                        //    System.err.println(numberOfMessagesReceived + ": ** Our Turn ** " + mes.toString());
                        //}

                        waitingForOurTurn = false;

                        // Clear some per-turn variables.
                        // For others, see above: if (mesType == SOCMessage.TURN)
                        whatWeFailedToBuild = null;
                        failedBuildingAttempts = 0;
                        rejectedPlayDevCardType = -1;
                        rejectedPlayInvItem = null;
                    }

                    /**
                     * Handle some message types early.
                     *
                     * When reading the main flow of this method, skip past here;
                     * search for "it's time to decide to build or take other normal actions".
                     */
                    switch (mesType)
                    {
                    case SOCMessage.PLAYERELEMENT:
                        // If this during the ROLL_OR_CARD state, also updates the
                        // negotiator's is-selling flags.
                        // If our player is losing a resource needed for the buildingPlan, 
                        // clear the plan if this is for the Special Building Phase (on the 6-player board).
                        // In normal game play, we clear the building plan at the start of each turn.

                        handlePLAYERELEMENT((SOCPlayerElement) mes);
                        break;

                    case SOCMessage.PLAYERELEMENTS:
                        // Multiple PLAYERELEMENT updates;
                        // see comment above for actions taken.

                        handlePLAYERELEMENTS((SOCPlayerElements) mes);
                        break;

                    case SOCMessage.RESOURCECOUNT:
                        handlePLAYERELEMENT
                            (null, ((SOCResourceCount) mes).getPlayerNumber(), SOCPlayerElement.SET,
                             PEType.RESOURCE_COUNT, ((SOCResourceCount) mes).getCount());
                        break;

                    case SOCMessage.DICERESULT:
                        handleDICERESULT((SOCDiceResult) mes);
                    	break;

                    case SOCMessage.PUTPIECE:
                        handlePUTPIECE_updateGameData((SOCPutPiece) mes);
                        // For initial roads, also tracks their initial settlement in SOCPlayerTracker.
                        break;

                    case SOCMessage.MOVEPIECE:
                        {
                            SOCMovePiece mpm = (SOCMovePiece) mes;
                            SOCShip sh = new SOCShip
                                (game.getPlayer(mpm.getPlayerNumber()), mpm.getFromCoord(), null);
                            game.moveShip(sh, mpm.getToCoord());
                        }
                        break;

                    case SOCMessage.CANCELBUILDREQUEST:
                        handleCANCELBUILDREQUEST((SOCCancelBuildRequest) mes);
                        break;

                    case SOCMessage.MOVEROBBER:
                        robberMoved(((SOCMoveRobber) mes).getCoordinates());
                        break;

                    case SOCMessage.MAKEOFFER:
                        if (robotParameters.getTradeFlag() == 1) {
                            handleMAKEOFFER((SOCMakeOffer) mes);
                            //useful for debugging trade interactions
                            //if (ourPlayerData.getName().contains("partial") && ourTurn) {
                            //    SOCMakeOffer om = (SOCMakeOffer)mes;
                            //    boolean[] to = om.getOffer().getTo();
                            //    String printString = "From:" + om.getOffer().getFrom() + " To:"; // + to[0] + "|" + to[1] + "|" + to[2] + "|" + to[3];
                            //    if (to[0])
                            //        printString += "T";
                            //    else
                            //        printString += "F";
                            //    for (int i = 1; i < to.length; i++) {
                            //        if (to[i])
                            //            printString += ("," + "T");
                            //        else
                            //            printString += ("," + "F");
                            //    }
                            //    if (om.getOffer().getFrom() == ourPN) {
                            //        //our offer
                            //        printString += (" Give=" + om.getOffer().getGiveSet() + " Get=" + om.getOffer().getGetSet());
                            //        if (om.getOffer().getGiveSet().getTotal() == 0) {
                            //            System.err.println(numberOfMessagesReceived + ": Partl: " + printString); //mes.toString());
                            //        } else {
                            //            System.err.println(numberOfMessagesReceived + ": Offer: " + printString); //mes.toString());
                            //        }
                            //    } else {
                            //        //somebody else's offer
                            //        printString += (" Give=" + om.getOffer().getGetSet() + " Get=" + om.getOffer().getGiveSet());
                            //        System.err.println(numberOfMessagesReceived + ": RetOf: " + printString); //mes.toString());
                            //    }
                            //}
                        }
                        break;

                    case SOCMessage.CLEAROFFER:
                        if (robotParameters.getTradeFlag() == 1)
                        {
                            final int pn = ((SOCClearOffer) mes).getPlayerNumber();
                            if (pn != -1)
                            {
                                game.getPlayer(pn).setCurrentOffer(null);
                            } else {
                                for (int i = 0; i < game.maxPlayers; ++i)
                                    game.getPlayer(i).setCurrentOffer(null);
                            }
//attempt to fix hanging robot after accepting a counteroffer 
//                            clearTradingFlags();
                        }
                        break;

                    case SOCMessage.ACCEPTOFFER:
                        if (waitingForTradeResponse && (robotParameters.getTradeFlag() == 1))
                        {
                            final int acceptingPN = ((SOCAcceptOffer) mes).getAcceptingNumber();

                            if (((SOCAcceptOffer) mes).getOfferingNumber() == ourPN)
                            {
                                handleTradeResponse(acceptingPN, true);
                            }
                            else if (acceptingPN < 0)
                            {
                                clearTradingFlags(false, false);
                            }

                            //useful for debugging trade interactions
                            //if (ourPlayerData.getName().contains("partial") && ourTurn) {
                            //    System.err.println(numberOfMessagesReceived + ": Accept: " + mes.toString());
                            //}
                        }
                        break;

                    case SOCMessage.REJECTOFFER:
                        if (robotParameters.getTradeFlag() == 1)
                            handleREJECTOFFER((SOCRejectOffer) mes);
                        //useful for debugging trade interactions
                        //if (ourPlayerData.getName().contains("partial") && ourTurn) {
                        //    System.err.println(numberOfMessagesReceived + ": Reject: " + mes.toString());
                        //}
                        break;

                    case SOCMessage.DEVCARDACTION:
                        {
                            SOCDevCardAction dcMes = (SOCDevCardAction) mes;
                            if (dcMes.getAction() != SOCDevCardAction.CANNOT_PLAY)
                            {
                                handleDEVCARD(dcMes);
                            } else {
                                // rejected by server, can't play our requested card
                                rejectedPlayDevCardType = dcMes.getCardType();
                                waitingForGameState = false;
                                expectPLACING_FREE_ROAD1 = false;
                                expectWAITING_FOR_DISCOVERY = false;
                                expectWAITING_FOR_MONOPOLY = false;
                                expectPLACING_ROBBER = false;
                            }
                        }
                        break;

                    case SOCMessage.SIMPLEREQUEST:
                        // These messages can almost always be ignored by bots,
                        // unless we've just sent a request to attack a pirate fortress.
                        // Some request types are handled at the bottom of the loop body;
                        // search for SOCMessage.SIMPLEREQUEST

                        if (ourTurn && waitingForSC_PIRI_FortressRequest)
                        {
                            final SOCSimpleRequest rqMes = (SOCSimpleRequest) mes;

                            if ((rqMes.getRequestType() == SOCSimpleRequest.SC_PIRI_FORT_ATTACK)
                                && (rqMes.getPlayerNumber() == -1))
                            {
                                // Attack request was denied: End our turn now.
                                // Reset method sets waitingForGameState, which will bypass
                                // any further actions in the run() loop body.

                                waitingForSC_PIRI_FortressRequest = false;
                                resetFieldsAtEndTurn();
                                client.endTurn(game);
                            }
                            // else, from another player; we can ignore it
                        }
                        break;

                    case SOCMessage.SIMPLEACTION:
                        // Most action types are handled later in the loop body;
                        // search for SOCMessage.SIMPLEACTION

                        switch(((SOCSimpleAction) mes).getActionType())
                        {
                        case SOCSimpleAction.SC_PIRI_FORT_ATTACK_RESULT:
                            if (ourTurn && waitingForSC_PIRI_FortressRequest)
                            {
                                // Our player has won or lost an attack on a pirate fortress.
                                // When we receive this message, other messages have already
                                // been sent to update related game state. End our turn now.
                                // Reset method sets waitingForGameState, which will bypass
                                // any further actions in the run() loop body.

                                waitingForSC_PIRI_FortressRequest = false;
                                resetFieldsAtEndTurn();
                                // client.endTurn not needed; making the attack implies sending endTurn
                            }
                            // else, from another player; we can ignore it

                            break;
                        }
                        break;

                    case SOCMessage.INVENTORYITEMACTION:
                        if (((SOCInventoryItemAction) mes).action == SOCInventoryItemAction.CANNOT_PLAY)
                        {
                            final List<SOCInventoryItem> itms = ourPlayerData.getInventory().getByStateAndType
                                (SOCInventory.PLAYABLE, ((SOCInventoryItemAction) mes).itemType);
                            if (itms != null)
                                rejectedPlayInvItem = itms.get(0);  // any item of same type# is similar enough here

                            waitingForGameState = false;
                            expectPLACING_INV_ITEM = false;  // in case was rejected placement (SC_FTRI gift port, etc)
                        }
                        break;

                    case SOCMessage.GAMETEXTMSG:
                        // Let the brain handle inter-agent text communication (possibly vacuously)
                        SOCGameTextMsg gtm = (SOCGameTextMsg) mes;
                        String sender = gtm.getNickname();
                        // Ignore messages which come from the server, the pinger, or yourself
                        if (!sender.equals("Server")
                                && !sender.equals("*PING*")
                                && !sender.equals(client.getNickname())) {
                            handleChat(gtm);
                        }
                        else {
                        	handleGameTxtMsg(gtm);
                        }
                        break;
                    case SOCMessage.PARSERESULT:
                        handleParseResult((SOCParseResult)mes);
                        break;
                    }  // switch(mesType)

                    debugInfo();

                    if ((game.getGameState() == SOCGame.ROLL_OR_CARD) && ! waitingForGameState)
                    {
                        rollOrPlayKnightOrExpectDice();

                        // On our turn, ask client to roll dice or play a knight;
                        // on other turns, update flags to expect dice result.
                        // Clears expectROLL_OR_CARD to false.
                        // Sets either expectDICERESULT, or expectPLACING_ROBBER and waitingForGameState.
                    }

                    if (ourTurn && (game.getGameState() == SOCGame.WAITING_FOR_ROBBER_OR_PIRATE) && ! waitingForGameState)
                    {
                        // TODO handle moving the pirate too
                        // For now, always decide to move the robber.
                        // Once we move the robber, will also need to deal with state WAITING_FOR_ROB_CLOTH_OR_RESOURCE.
                        expectPLACING_ROBBER = true;
                        waitingForGameState = true;
                        counter = 0;
                        client.choosePlayer(game, SOCChoosePlayer.CHOICE_MOVE_ROBBER);
                        pause(200);
                    }

                    else if ((game.getGameState() == SOCGame.PLACING_ROBBER) && ! waitingForGameState)
                    {
                        if ((! waitingForOurTurn) && ourTurn)
                        {
                            if (! ((expectROLL_OR_CARD || expectPLAY1) && (counter < 4000)))
                            {
                            	//MOVEROBBER: save
//                            	if(!saved){
//                            		saveGame();
//                            	}
                            	moveRobber();
                                    // call before updating expect/waitingFor fields,
                                    // in case a 3rd-party bot wants to note/save current brain state
                                counter = 0;

                                if (moveRobberOnSeven)
                                {
                                    // robber moved because 7 rolled on dice
                                    moveRobberOnSeven = false;
                                    waitingForGameState = true;
                                    expectPLAY1 = true;
                                }
                                else
                                {
                                    waitingForGameState = true;

                                    if (oldGameState == SOCGame.ROLL_OR_CARD)
                                    {
                                        // robber moved from playing knight card before dice roll
                                        expectROLL_OR_CARD = true;
                                    }
                                    else if (oldGameState == SOCGame.PLAY1)
                                    {
                                        // robber moved from playing knight card after dice roll
                                        expectPLAY1 = true;
                                    }
                                }
                            }
                        }

                        expectPLACING_ROBBER = false;
                    }

                    if ((game.getGameState() == SOCGame.WAITING_FOR_DISCOVERY) && ! waitingForGameState)
                    {
                        if ((! waitingForOurTurn) && ourTurn)
                        {
                            if (! (expectPLAY1) && (counter < 4000))
                            {
                            	//Choose discovery save
//                            	if(!saved){
//                            		saveGame();
//                            	}
                                waitingForGameState = true;
                                expectPLAY1 = true;
                                counter = 0;
                                client.pickResources(game, decisionMaker.resourceChoices);
                                pause(3);
                            }
                        }
                        expectWAITING_FOR_DISCOVERY = false;
                    }

                    if ((game.getGameState() == SOCGame.WAITING_FOR_MONOPOLY) && ! waitingForGameState)
                    {
                        if ((! waitingForOurTurn) && ourTurn)
                        {
                            if (!(expectPLAY1) && (counter < 4000))
                            {
                            	//Choose monopoly save
//                            	if(!saved){
//                            		saveGame();
//                            	}
                                waitingForGameState = true;
                                expectPLAY1 = true;
                                counter = 0;
                                client.pickResourceType(game, monopolyStrategy.getMonopolyChoice());
                                pause(3);
                            }
                        }
                        expectWAITING_FOR_MONOPOLY = false;
                    }

                    if (ourTurn && (! waitingForOurTurn)
                        && (game.getGameState() == SOCGame.PLACING_INV_ITEM) && (! waitingForGameState))
                    {
                        planAndPlaceInvItem();  // choose and send a placement location
                    }

                    if (waitingForTradeMsg && (mesType == SOCMessage.BANKTRADE))
                    {
                        final int pn = ((SOCBankTrade) mes).getPlayerNumber();
                        final boolean wasAllowed = (pn >= 0);

                        if ((pn == ourPlayerNumber) || ! wasAllowed)
                            //
                            // This is the bank/port trade confirmation announcement we've been waiting for
                            //
                            clearTradingFlags(true, wasAllowed);

                        //  NB: It would be preferable to check for the actual appropriate message type, but unfortunately bank trade messages are processed
                        //      prior to the processing of the associated resource exchange.
                    }

                    if (waitingForDevCard && (mesType == SOCMessage.SIMPLEACTION)
                        && (((SOCSimpleAction) mes).getPlayerNumber() == ourPlayerNumber)
                        && (((SOCSimpleAction) mes).getActionType() == SOCSimpleAction.DEVCARD_BOUGHT))
                    {
                        //
                        // This is the "dev card bought" message we've been waiting for
                        //
                        waitingForDevCard = false;
                    }

                    /**
                     * Planning: If our turn and not waiting for something,
                     * it's time to decide to build or take other normal actions.
                     */
                    if (! isWaiting() && ((game.getGameState() == SOCGame.PLAY1) || (game.getGameState() == SOCGame.SPECIAL_BUILDING))
                        && ! (waitingForGameState || waitingForTradeMsg || waitingForTradeResponse || waitingForDevCard
                              || expectPLACING_ROAD || expectPLACING_SETTLEMENT || expectPLACING_CITY
                              || expectPLACING_SHIP || expectPLACING_FREE_ROAD1 || expectPLACING_FREE_ROAD2
                              || expectPLACING_ROBBER || expectWAITING_FOR_DISCOVERY || expectWAITING_FOR_MONOPOLY
                              || waitingForSC_PIRI_FortressRequest || (waitingForPickSpecialItem != null)))
                    {
                        expectPLAY1 = false;

                        // 6-player: check Special Building Phase
                        // during other players' turns.
                        if ((! ourTurn) && waitingForOurTurn && gameIs6Player
                             && (! decidedIfSpecialBuild) && (! expectPLACING_ROBBER))
                        {
                            decidedIfSpecialBuild = true;

                            /**
                             * It's not our turn.  We're not doing anything else right now.
                             * Gamestate has passed ROLL_OR_CARD, so we know what resources to expect.
                             * Do we want to Special Build?  Check the same conditions as during our turn.
                             * Make a plan if we don't have one,
                             * and if we haven't given up building attempts this turn.
                             */

                            if (getBuildingPlan().isEmpty()
                                && (ourPlayerData.getResources().getTotal() > 1)
                                && (failedBuildingAttempts < MAX_DENIED_BUILDING_PER_TURN)
                                && ! (game.isGameOptionSet("PLP") && (game.getPlayerCount() < 5)))
                            {
                            	resetBuildingPlan();
                                planBuilding();

                                /*
                                 * planBuilding takes these actions, sets buildingPlan and other fields
                                 * (see its javadoc):
                                 *
                                decisionMaker.planStuff(robotParameters.getStrategyType());

                                if (!buildingPlan.empty())
                                {
                                    lastTarget = (SOCPossiblePiece) buildingPlan.peek();
                                    negotiator.setTargetPiece(ourPlayerNumber, buildingPlan.peek());
                                }
                                 */

                                BP plan = getBuildingPlan();
                                if ( ! plan.isEmpty())
                                {
                                    // If we have the resources right now, ask to Special Build

                                    final SOCPossiblePiece targetPiece = plan.getPlannedPiece(0);
                                    final SOCResourceSet targetResources = targetPiece.getResourcesToBuild();
                                        // may be null

                                    if ((ourPlayerData.getResources().contains(targetResources)))
                                    {
                                        // Ask server for the Special Building Phase.
                                        // (TODO) if FAST_STRATEGY: Maybe randomly don't ask, to lower opponent difficulty?
                                        waitingForSpecialBuild = true;
                                        client.buildRequest(game, -1);
                                        pause(1); // TODO: This was 100, which I didn't find prior to refactoring the pause functionality.  Whoops.
                                    }
                                }
                            }
                        }

                        if ((! waitingForOurTurn) && ourTurn)
                        {
                            if (! (expectROLL_OR_CARD && (counter < 4000)))
                            {
                                counter = 0;

                                //D.ebugPrintln("DOING PLAY1");
                                if (D.ebugOn)
                                {
                                    client.sendText(game, "================================");

                                    // for each player in game:
                                    //    sendText and debug-prn game.getPlayer(i).getResources()
                                    printResources();
                                }

                                // TODO: Game logic starts here...
                                planAndDoActionForPLAY1();
                            }
                        }
                    }

                    /**
                     * Placement: Make various putPiece calls; server has told us it's OK to buy them.
                     * Call client.putPiece.
                     * Works when it's our turn and we have an expect flag set
                     * (such as expectPLACING_SETTLEMENT, in these game states:
                     * START1A - START2B or - START3B
                     * PLACING_SETTLEMENT, PLACING_ROAD, PLACING_CITY
                     * PLACING_FREE_ROAD1, PLACING_FREE_ROAD2
                     */
                    if (! waitingForGameState)
                    {
                        placeIfExpectPlacing();
                    }

                    /**
                     * End of various putPiece placement calls.
                     */

                    /*
                       if (game.getGameState() == SOCGame.OVER) {
                       client.leaveGame(game);
                       alive = false;
                       }
                     */

                    /**
                     * Handle various message types here at bottom of loop.
                     */
                    switch (mesType)
                    {
                    case SOCMessage.PUTPIECE:
                        /**
                         * this is for player tracking
                         *
                         * For initial placement of our own pieces, also checks
                         * and clears expectPUTPIECE_FROM_START1A,
                         * and sets expectSTART1B, etc.  The final initial putpiece
                         * clears expectPUTPIECE_FROM_START2B and sets expectROLL_OR_CARD.
                         */
                        {
                            final SOCPutPiece mpp = (SOCPutPiece) mes;
                            final int pn = mpp.getPlayerNumber();
                            final int coord = mpp.getCoordinates();
                            final int pieceType = mpp.getPieceType();
                            handlePUTPIECE_updateTrackers(pn, coord, pieceType);
                        }

                        break;

                    case SOCMessage.MOVEPIECE:
                        /**
                         * this is for player tracking of moved ships
                         */
                        {
                            final SOCMovePiece mpp = (SOCMovePiece) mes;
                            final int pn = mpp.getPlayerNumber();
                            final int coord = mpp.getToCoord();
                            final int pieceType = mpp.getPieceType();
                            // TODO what about getFromCoord()? Should mark that loc as unoccupied in trackers
                            handlePUTPIECE_updateTrackers(pn, coord, pieceType);
                        }
                        break;

                    case SOCMessage.DICERESULT:
                        if (expectDICERESULT)
                        {
                            expectDICERESULT = false;

                            if (((SOCDiceResult) mes).getResult() == 7)
                            {
                                final boolean robWithoutRobber = game.isGameOptionSet(SOCGameOptionSet.K_SC_PIRI);
                                    // In scenario SC_PIRI there's no robber to be moved. Instead,
                                    // current player will be prompted soon to choose a player to rob on 7

                                if (! robWithoutRobber)
                                    moveRobberOnSeven = true;
    
                                if (ourPlayerData.getResources().getTotal() > 7)
                                {
                                    expectDISCARD = true;
                                } else if (ourTurn) {
                                    if (! robWithoutRobber)
                                        expectPLACING_ROBBER = true;
                                    else
                                        expectPLAY1 = true;
                                }
                            }
                            else
                            {
                                expectPLAY1 = true;
                            }
                        }
                        break;

                    case SOCMessage.SIMPLEREQUEST:
                        {
                            // Some request types are handled at the top of the loop body;
                            //   search for SOCMessage.SIMPLEREQUEST
                            // Some are handled here
                            // Most can be ignored by bots

                            final SOCSimpleRequest rqMes = (SOCSimpleRequest) mes;
                            switch (rqMes.getRequestType())
                            {
                            case SOCSimpleRequest.PROMPT_PICK_RESOURCES:
                                // gold hex
                                counter = 0;
                                // try to make a plan if we don't have one
                                if (buildingPlan.isEmpty())
                                    planBuilding();
                                client.pickResources(game, decisionMaker.pickFreeResources(rqMes.getValue1()));
                                waitingForGameState = true;
                                if (game.isInitialPlacement())
                                {
                                    if (game.isGameOptionSet(SOCGameOptionSet.K_SC_3IP))
                                        expectSTART3B = true;
                                    else
                                        expectSTART2B = true;
                                } else {
                                    expectPLAY1 = true;
                                }
                                break;
                            }
                        }
                        break;

                    case SOCMessage.DISCARDREQUEST:
                    	expectDISCARD = false;

                        if ((game.getCurrentDice() == 7) && ourTurn)
                        {
                            if (! game.isGameOptionSet(SOCGameOptionSet.K_SC_PIRI))
                                expectPLACING_ROBBER = true;
                            else
                                expectPLAY1 = true;
                        }
                        else
                        {
                            expectPLAY1 = true;
                        }

                        counter = 0;
                        client.discard(game, discardStrategy.discard
                            (((SOCDiscardRequest) mes).getNumberOfDiscards(), buildingPlan));

                        break;

                    case SOCMessage.CHOOSEPLAYERREQUEST:
                        {
                            final SOCChoosePlayerRequest msg = (SOCChoosePlayerRequest) mes;
                            final int choicePl = robberStrategy.chooseRobberVictim
                                (msg.getChoices(), msg.canChooseNone());
                            counter = 0;
                            client.choosePlayer(game, choicePl);
                        }
                        break;

                    case SOCMessage.CHOOSEPLAYER:
                        {
                            final int vpn = ((SOCChoosePlayer) mes).getChoice();
                            // Cloth is more valuable.
                            // TODO decide when we should choose resources instead
                            client.choosePlayer(game, -(vpn + 1));
                        }
                        break;

                    case SOCMessage.SETSPECIALITEM:
                        if (waitingForPickSpecialItem != null)
                        {
                            final SOCSetSpecialItem siMes = (SOCSetSpecialItem) mes;
                            if (siMes.typeKey.equals(waitingForPickSpecialItem))
                            {
                                // This could be the "pick special item" message we've been waiting for,
                                // or a related SET/CLEAR message that precedes it

                                switch (siMes.op)
                                {
                                case SOCSetSpecialItem.OP_PICK:
                                    waitingForPickSpecialItem = null;

                                    // Now that this is received, can continue our turn.
                                    // Any specific action needed? Not for SC_WOND.
                                    break;

                                case SOCSetSpecialItem.OP_DECLINE:
                                    waitingForPickSpecialItem = null;

                                    // TODO how to prevent asking again? (similar to whatWeFailedtoBuild)
                                    break;

                                // ignore SET or CLEAR that precedes the PICK message
                                }
                            }
                        }
                        break;

                    case SOCMessage.ROBOTDISMISS:
                        if ((! expectDISCARD) && (! expectPLACING_ROBBER))
                        {
                            client.leaveGame(game, "dismiss msg", false, false);
                            alive = false;
                        }
                        break;

                    case SOCMessage.TIMINGPING:
                        // Once-per-second message from the pinger thread
                        counter++;
                        break;

                    }  // switch (mesType) - for some types, at bottom of loop body

                    if (ourTurn && (counter > 15000))
                    {
                        // We've been waiting too long, must be a bug: Leave the game.
                        // This is a fallback, server has SOCForceEndTurnThread which
                        // should have already taken action.
                        // Before v1.1.20, would leave game even during other (human) players' turns.
                    	// TODO: Debug here?
                        client.leaveGame(game, "counter 15000", true, false);
                        alive = false;
                    }

                    if ((failedBuildingAttempts > (2 * MAX_DENIED_BUILDING_PER_TURN))
                        && game.isInitialPlacement())
                    {
                        // Apparently can't decide where we can initially place:
                        // Leave the game.
                    	// TODO: Debug here?
                        client.leaveGame(game, "failedBuildingAttempts at start", true, false);
                        alive = false;
                    }

                    /*
                       if (D.ebugOn) {
                       if (mes != null) {
                       debugInfo();
                       D.ebugPrintln("~~~~~~~~~~~~~~~~");
                       }
                       }
                     */

                    Thread.yield();
                }
            }catch(InterruptedException e){
            	//do nothing as the clean up is executed after exiting the catch block 
            }
            catch (Exception e)
            {
                // Print exception; ignore errors due to game reset in another thread
                if (alive && ((game == null) || (game.getGameState() != SOCGame.RESET_OLD)))
                {
                    ++turnExceptionCount;  // TODO end our turn if too many

                    String eMsg = (turnExceptionCount == 1)
                        ? "*** Robot " + ourPlayerName + " caught an exception - " + e
                        : "*** Robot " + ourPlayerName + " caught an exception (" + turnExceptionCount + " this turn) - " + e;
                    D.ebugPrintlnINFO(eMsg);
                    System.err.println(eMsg);
                    e.printStackTrace();
                }
            }
        }
        else
        {
            System.err.println("AGG! NO PINGER!");
        }

        //D.ebugPrintln("STOPPING AND DEALLOCATING");
        gameEventQ = null;

        client.addCleanKill();
        client = null;

        game = null;
        ourPlayerData = null;
        dummyCancelPlayerData = null;
        whatWeWantToBuild = null;
        whatWeFailedToBuild = null;
        rejectedPlayInvItem = null;
        ourPlayerTracker = null;
        playerTrackers = null;

        pinger.stopPinger();
        pinger = null;
    }

    /**
     * Bot is ending its turn; reset state control fields to act during other players' turns.
     *<UL>
     * <LI> {@link #waitingForGameState} = true
     * <LI> {@link #expectROLL_OR_CARD} = true
     * <LI> {@link #waitingForOurTurn} = true
     * <LI> {@link #doneTrading} = false only if {@link #robotParameters} allow trade
     * <LI> {@link #counter} = 0
     * <LI> clear/{@link #resetBuildingPlan()}
     * <LI> {@link SOCRobotNegotiator#resetIsSelling() negotiator.resetIsSelling()},
     *      {@link SOCRobotNegotiator#resetOffersMade() .resetOffersMade()},
     *      {@link SOCRobotNegotiator#resetTargetPieces() .resetTargetPieces()}
     *</UL>
     *<P>
     * Called only after {@link #endTurnActions()} returns true.
     * Does not call {@link SOCRobotClient#endTurn(SOCGame)}.
     * @since 2.0.00
     */
    protected void resetFieldsAtEndTurn()
    {
        waitingForGameState = true;
        counter = 0;
        expectROLL_OR_CARD = true;
        waitingForOurTurn = true;

        doneTrading = (robotParameters.getTradeFlag() != 1);

        //D.ebugPrintln("!!! ENDING TURN !!!");
        negotiator.resetIsSelling();
        negotiator.resetOffersMade();
        negotiator.resetTradesMade();
        resetBuildingPlan();
        negotiator.resetTargetPieces();
    }

    /**
     * Look for and take any scenario-specific final actions before ending the turn.
     * Is called before {@link #endTurnActions()}.
     *<P>
     * For example, {@link SOCGameOptionSet#K_SC_PIRI _SC_PIRI} will check if we've reached the fortress
     * and have 5 or more warships, and if so will attack the fortress.  Doing so ends the turn, so
     * we don't try to attack before end of turn.
     *<P>
     * {@link SOCGameOptionSet#K_SC_FTRI _SC_FTRI} can play a gift port from our inventory to place for
     * better bank trades.
     *<P>
     * <B>NOTE:</B> For now this method assumes it's called only in the {@code SC_FTRI} or {@code SC_PIRI} scenario.
     * Caller must check the game for any relevant scenario SOCGameOptions before calling.
     * Also assumes not {@link #waitingForGameState} or any other pending action.
     *
     * @return true if an action was taken <B>and</B> turn shouldn't be ended yet, false otherwise
     * @since 2.0.00
     */
    protected boolean considerScenarioTurnFinalActions()
    {
        // NOTE: for now this method assumes it's called only in the SC_FTRI or SC_PIRI scenario

        if (game.isGameOptionSet(SOCGameOptionSet.K_SC_FTRI))
        {
            // SC_FTRI

            // check inventory for gift ports
            SOCInventoryItem itm = null;
            for (SOCInventoryItem i : ourPlayerData.getInventory().getByState(SOCInventory.PLAYABLE))
            {
                if (i.itype > 0)
                    continue;  // not a port; most likely a SOCDevCard
                if ((rejectedPlayInvItem != null) && (i.itype == rejectedPlayInvItem.itype))
                    continue;

                itm = i;
                break;  // unlikely to have more than one in inventory
            }

            if (itm != null)
            {
                // Do we have somewhere to place one?
                if (ourPlayerData.getPortMovePotentialLocations(false) == null)
                    return false;

                // Set fields, make the request
                return planAndPlaceInvItemPlacement_SC_FTRI(itm);
            }
        } else {
            // SC_PIRI

            // require 5+ warships; game.canAttackPirateFortress checks that we've reached the fortress with adjacent ship
            if ((ourPlayerData.getNumWarships() < 5) || (null == game.canAttackPirateFortress()))
                return false;

            waitingForSC_PIRI_FortressRequest = true;
            client.simpleRequest(game, ourPlayerNumber, SOCSimpleRequest.SC_PIRI_FORT_ATTACK, 0, 0);

            return true;
        }

        return false;
    }

    /**
     * Plan what to do during {@code PLAY1} game state and do that planned action, or end turn.
     * Calls some or all of these strategy/decision methods, which third-party bots may override:
     *<UL>
     * <LI> {@link #playKnightCardIfShould()}
     * <LI> {@link #planBuilding()}
     * <LI> {@link #buildOrGetResourceByTradeOrCard()}
     * <LI> {@link #considerScenarioTurnFinalActions()}
     *</UL>
     * If nothing to do, will call {@link #resetFieldsAtEndTurn()} and {@link SOCRobotClient#endTurn(SOCGame)}.
     *<P>
     * Third-party bots may instead choose to override this entire method.
     * NOTE: method required for SmartSettlers agent to override
     */
    protected void planAndDoActionForPLAY1()
    {
        /**
         * if we haven't played a dev card yet, and we have a knight, 
         * decide if we should play it for the purpose of acquiring largest army
         *
         * If we're in SPECIAL_BUILDING (not PLAY1),
         * can't trade or play development cards.
         *
         * In scenario _SC_PIRI (which has no robber and
         * no largest army), play one whenever we have
         * it, someone else has resources, and we can
         * convert a ship to a warship.
         */
        if ((game.getGameState() == SOCGame.PLAY1) && ! ourPlayerData.hasPlayedDevCard())
        {
            playKnightCardIfShould();  // might set expectPLACING_ROBBER and waitingForGameState
        }

        /**
         * make a plan if we don't have one,
         * and if we haven't given up building
         * attempts this turn.
         */
        if ( (! expectPLACING_ROBBER) && (getBuildingPlan().isEmpty())
             && (ourPlayerData.getResources().getTotal() > 1)
             && (failedBuildingAttempts < MAX_DENIED_BUILDING_PER_TURN))
        {
        	resetBuildingPlan();
            planBuilding();

            /*
             * planBuilding takes these actions, sets buildingPlan and other fields
             * (see its javadoc):
             *
            decisionMaker.planStuff(robotParameters.getStrategyType());

            if (!buildingPlan.empty())
            {
                lastTarget = (SOCPossiblePiece) buildingPlan.peek();
                negotiator.setTargetPiece(ourPlayerNumber, buildingPlan.peek());
            }
             */
            
            //---MG
            //debugPrintBrainStatus();
        }

        //D.ebugPrintln("DONE PLANNING");
        BP plan = getBuildingPlan();
        if (! expectPLACING_ROBBER && ! plan.isEmpty())
        {
            // Time to build something.

            // Either ask to build a piece, or use trading or development
            // cards to get resources to build it.  See javadoc for flags set.
            buildOrGetResourceByTradeOrCard(plan);
        }

        /**
         * see if we're done with our turn
         */
        if (! (isWaiting() || expectPLACING_SETTLEMENT || expectPLACING_FREE_ROAD1 || expectPLACING_FREE_ROAD2
               || expectPLACING_ROAD || expectPLACING_CITY || expectPLACING_SHIP
               || expectWAITING_FOR_DISCOVERY || expectWAITING_FOR_MONOPOLY
               || expectPLACING_ROBBER || waitingForTradeMsg || waitingForTradeResponse
               || waitingForDevCard
               || waitingForGameState
               || (waitingForPickSpecialItem != null)))
        {
            // Any last things for turn from game's scenario?
            boolean scenActionTaken = false;
            if (game.isGameOptionSet(SOCGameOptionSet.K_SC_FTRI)
                || game.isGameOptionSet(SOCGameOptionSet.K_SC_PIRI))
            {
                // possibly attack pirate fortress
                // or place a gift port for better bank trades
                scenActionTaken = considerScenarioTurnFinalActions();
            }

            boolean finishTurnNow = (! scenActionTaken) && endTurnActions();

            if (finishTurnNow)
            {
                resetFieldsAtEndTurn();
                    /*
                     * These state fields are reset:
                     *
                    waitingForGameState = true;
                    counter = 0;
                    expectROLL_OR_CARD = true;
                    waitingForOurTurn = true;

                    doneTrading = (robotParameters.getTradeFlag() != 1);

                    //D.ebugPrintln("!!! ENDING TURN !!!");
                    negotiator.resetIsSelling();
                    negotiator.resetOffersMade();
                    negotiator.resetTradesMade();
                    resetBuildingPlan();
                    negotiator.resetTargetPieces();
                     */

                pause(3);
                client.endTurn(game);
            }
        }
		
    }

    /**
     * We need this method to override it in children classes. 
     *<P>
     * Handle a game state change from {@link SOCGameState} or another message
     * which has a Game State field. Clears {@link #waitingForGameState}
     * (unless {@code newState} is {@link SOCGame#LOADING} or {@link SOCGame#LOADING_RESUMING}),
     * updates {@link #oldGameState} if state value is actually changing, then calls
     * {@link SOCDisplaylessPlayerClient#handleGAMESTATE(SOCGame, int)}.
     *<P>
     * When state moves from {@link SOCGame#ROLL_OR_CARD} to {@link SOCGame#PLAY1},
     * calls {@link #startTurnMainActions()}.
     *
     * @param newState  New game state, like {@link SOCGame#ROLL_OR_CARD}; if 0, does nothing
     */
    protected void handleGAMESTATE(final int newState)
    {
        if (newState == 0)
            return;

        waitingForGameState = ((gs == SOCGame.LOADING) || (gs == SOCGame.LOADING_RESUMING));  // almost always false
        int currGS = game.getGameState();
        if (currGS != newState)
            oldGameState = currGS;  // if no actual change, don't overwrite previously known oldGameState

        // Special handling for legacy state update.  Allow legacy agents to 
        //  treat this as they originally did, so we can contrast performance.  Non-legacy
        //  agents should ignore this game state.
        if (newState == SOCGame.PLAY1_LEGACY) {
            if (isLegacy()) {
                newState = SOCGame.PLAY1;
            }
        }

        SOCDisplaylessPlayerClient.handleGAMESTATE(game, newState);

        if (newState == SOCGame.ROLL_OR_CARD)
        {
            // probably need to restrict - currently will call this after every action within a turn.  Set a flag when TURN is issued, unset here
            waitingForTurnMain = true;
        }
        else if ((gs == SOCGame.PLAY1) && waitingForTurnMain)
        {
            startTurnMainActions();
            waitingForTurnMain = false;
        }
    }
	
	/**
     * We need this method to override it in children classes. 
     * All it does is to set the dice result in the SOCGame object.
     * @param mes
     */
    protected void handleDICERESULT(SOCDiceResult mes) {
    	game.setCurrentDice(mes.getResult());
		
	}

	/**
     * We need this method to override it in children classes. 
     * All it does is to move the robber on the board inside the game object and to reset the moveRobberOnSeven flag.
     *<P>
     * Update game data and bot flags when robot or pirate has moved,
     * including clear {@link #moveRobberOnSeven} flag.
     * Third-party bots can override if needed; if so, be sure to call {@code super.robberMoved(..)}.
     *<P>
     * Doesn't call server-only {@link SOCGame#moveRobber(int, int)} because that would call the
     * functions to do the stealing. We just want to set where the robber moved.
     * Server's {@code MoveRobber} message will be followed by messages like {@code PlayerElement}
     * to report the gain/loss of resources.
     *
     * @param newHex  New hex coordinate of robber if &gt; 0, pirate if &lt;= 0 (invert before using)
     */
    protected void robberMoved(final int newHex)
    {
        moveRobberOnSeven = false;

        if (newHex > 0)
            game.getBoard().setRobberHex(newHex, true);
        else
            ((SOCBoardLarge) game.getBoard()).setPirateHex(-newHex, true);
    }

    /**
     * Stop waiting for responses to a trade offer, no one has accepted it.
     * Remember other players' responses or non-responses,
     * Call {@link SOCRobotClient#clearOffer(SOCGame) client.clearOffer},
     * clear {@link #waitingForTradeResponse} and {@link #counter}.
     * Call {@link SOCRobotNegotiator#recordResourcesFromNoResponse(SOCTradeOffer)}.
     * @see #clearTradingFlags(boolean, boolean)
     * @see #handleTradeResponse(int, boolean)
     * @since 1.1.09
     */
    protected void tradeStopWaitingClearOffer()
    {
        ///
        /// record which players said no by not saying anything
        ///
        SOCTradeOffer ourCurrentOffer = ourPlayerData.getCurrentOffer();

        if (ourCurrentOffer != null)
        {
            negotiator.recordResourcesFromNoResponse(ourCurrentOffer);

            pause(3);
            client.clearOffer(game);
            pause(1);
        }

        counter = 0;
        for (int i=0; i<waitingForTradeResponsePlayer.length; i++) {
            waitingForTradeResponsePlayer[i] = false;
        }
        waitingForTradeResponse = false;
    }

    /**
     * If we haven't played a dev card yet this turn, and we have a knight, and we can get
     * largest army, play the knight. Must be our turn and gameState {@code PLAY1}.
     * {@link SOCPlayer#hasPlayedDevCard() ourPlayerData.hasPlayedDevCard()} must be false.
     *<P>
     * In scenario {@code _SC_PIRI} (which has no robber and no largest army), play one
     * whenever we have it, someone else has resources, and we can convert a ship to a warship.
     *<P>
     * If we call {@link #playKnightCard()}, it sets the flags
     * {@code expectPLACING_ROBBER} and {@code waitingForGameState}.
     *
     * @see #rollOrPlayKnightOrExpectDice()
     * @since 2.0.00
     */
    private void playKnightCardIfShould()
    {
        // Make sure we have an old KNIGHT dev card, etc;
        // for _SC_PIRI, also checks if # of warships ships less than # of ships
        if (! game.canPlayKnight(ourPlayerNumber))
            return;

        final boolean canGrowArmy;

        if (game.isGameOptionSet(SOCGameOptionSet.K_SC_PIRI))
        {
            // Convert ship to warship:
            // Play whenever we have one and someone else has resources

            boolean anyOpponentHasRsrcs = false;
            for (int pn = 0; pn < game.maxPlayers; ++pn)
            {
                if ((pn == ourPlayerNumber) || game.isSeatVacant(pn))
                    continue;

                if (game.getPlayer(pn).getResources().getTotal() > 0)
                {
                    anyOpponentHasRsrcs = true;
                    break;
                }
            }

            canGrowArmy = anyOpponentHasRsrcs;
        } else {
            canGrowArmy = decisionMaker.shouldPlayKnightForLA();
        }

        if (canGrowArmy
            && (rejectedPlayDevCardType != SOCDevCardConstants.KNIGHT))
        {
            /**
             * play a knight card
             * (or, in scenario _SC_PIRI, a Convert to Warship card)
             */
            playKnightCard();  // sets expectPLACING_ROBBER, waitingForGameState
        }
    }

    /**
     * If it's our turn and we have an expect flag set
     * (such as {@link #expectPLACING_SETTLEMENT}), then
     * call {@link SOCRobotClient#putPiece(SOCGame, SOCPlayingPiece) client.putPiece}
     * ({@code game}, {@link #whatWeWantToBuild}).
     *<P>
     * Looks for one of these game states:
     *<UL>
     * <LI> {@link SOCGame#START1A} - {@link SOCGame#START3B}
     * <LI> {@link SOCGame#PLACING_SETTLEMENT}
     * <LI> {@link SOCGame#PLACING_ROAD}
     * <LI> {@link SOCGame#PLACING_CITY}
     * <LI> {@link SOCGame#PLACING_SHIP}
     * <LI> {@link SOCGame#PLACING_FREE_ROAD1}
     * <LI> {@link SOCGame#PLACING_FREE_ROAD2}
     *</UL>
     * Does nothing if {@link #waitingForGameState}, {@link #waitingForOurTurn}, <tt>! {@link #ourTurn}</tt>,
     * or if state's corresponding <tt>expectPLACING_&lt;piecetype&gt;</tt> flag isn't set.
     *<P>
     * If all goes well, server will reply with a PutPiece message
     * to be handled in {@link #handlePUTPIECE_updateTrackers(int, int, int)}.
     *
     * @see #buildRequestPlannedPiece()
     * @since 1.1.09
     */
    protected void placeIfExpectPlacing()
    {
        if (waitingForGameState)
            return;

        switch (game.getGameState())
        {
        case SOCGame.PLACING_SETTLEMENT:
            if (ourTurn && (! waitingForOurTurn) && (expectPLACING_SETTLEMENT))
            {
                	//placing settlement save
//                	if(!saved){
//                		saveGame();
//                	}
                expectPLACING_SETTLEMENT = false;
                waitingForGameState = true;
                counter = 0;
                expectPLAY1 = true;
    
                //D.ebugPrintln("!!! PUTTING PIECE "+whatWeWantToBuild+" !!!");
                pause(1);
                client.putPiece(game, whatWeWantToBuild);
                pause(2);
            }
            break;

        case SOCGame.PLACING_ROAD:
            if (ourTurn && (! waitingForOurTurn) && (expectPLACING_ROAD))
            {
                	//placing road save
//                	if(!saved){
//                		saveGame();
//                	}
                expectPLACING_ROAD = false;
                waitingForGameState = true;
                counter = 0;
                expectPLAY1 = true;

                pause(1);
                client.putPiece(game, whatWeWantToBuild);
                pause(2);
            }
            break;

        case SOCGame.PLACING_CITY:
            if (ourTurn && (! waitingForOurTurn) && (expectPLACING_CITY))
            {
                	//placing city save
//                	if(!saved){
//                		saveGame();
//                	}
                expectPLACING_CITY = false;
                waitingForGameState = true;
                counter = 0;
                expectPLAY1 = true;

                pause(1);
                client.putPiece(game, whatWeWantToBuild);
                pause(2);
            }
            break;

        case SOCGame.PLACING_SHIP:
            if (ourTurn && (! waitingForOurTurn) && (expectPLACING_SHIP))
            {
                expectPLACING_SHIP = false;
                waitingForGameState = true;
                counter = 0;
                expectPLAY1 = true;

                pause(500);
                client.putPiece(game, whatWeWantToBuild);
                pause(1000);
            }
            break;

        case SOCGame.PLACING_FREE_ROAD1:
            if (ourTurn && (! waitingForOurTurn) && (expectPLACING_FREE_ROAD1))
            {
                	//placing first free road save
//                	if(!saved){
//                		saveGame();
//                	}
                expectPLACING_FREE_ROAD1 = false;
                waitingForGameState = true;
                counter = 0;
                expectPLACING_FREE_ROAD2 = true;

                // D.ebugPrintln("!!! PUTTING PIECE 1 " + whatWeWantToBuild + " !!!");
                pause(1);
                client.putPiece(game, whatWeWantToBuild);  // either ROAD or SHIP
                pause(2);
            }
            break;

        case SOCGame.PLACING_FREE_ROAD2:
            if (ourTurn && (! waitingForOurTurn) && (expectPLACING_FREE_ROAD2))
            {
                	//placing second free road save
//                	if(!saved){
//                		saveGame();
//                	}
                expectPLACING_FREE_ROAD2 = false;
                waitingForGameState = true;
                counter = 0;
                expectPLAY1 = true;
    
                BP plan = getBuildingPlan(); //TODO: this is dangerous as we expect the plan not to be empty, but this may change in memory over time 
                SOCPossiblePiece posPiece = plan.advancePlan();
                //(SOCPossiblePiece) buildingPlan.pop();
    
                if (posPiece.getType() == SOCPossiblePiece.ROAD)
                    whatWeWantToBuild = new SOCRoad(ourPlayerData, posPiece.getCoordinates(), null);
                else
                    whatWeWantToBuild = new SOCShip(ourPlayerData, posPiece.getCoordinates(), null);

                // D.ebugPrintln("posPiece = " + posPiece);
                // D.ebugPrintln("$ POPPED OFF");
                // D.ebugPrintln("!!! PUTTING PIECE 2 " + whatWeWantToBuild + " !!!");
                pause(1);
                client.putPiece(game, whatWeWantToBuild);
                pause(2);
            }
            break;
            
        //same as with placing robber: do the action first and then change states for the following cases
        case SOCGame.START1A:
            {
                if ((! waitingForOurTurn) && ourTurn && (! (expectPUTPIECE_FROM_START1A && (counter < 4000))))
                {
                	//START1A: save
//                	if(!saved){
//                		saveGame();
//                	}
                    final int firstSettleNode = decisionMaker.planInitialSettlements();
                    placeFirstSettlement(firstSettleNode);
                    expectPUTPIECE_FROM_START1A = true;
                    waitingForGameState = true;
                    counter = 0;
                }

                expectSTART1A = false;
            }
            break;

            case SOCGame.START1B:
            {
                if ((! waitingForOurTurn) && ourTurn && (! (expectPUTPIECE_FROM_START1B && (counter < 4000))))
                {
                	//START1B: save
//                	if(!saved){
//                		saveGame();
//                	}
                    planAndPlaceInitRoad();

                    expectPUTPIECE_FROM_START1B = true;
                    counter = 0;
                    waitingForGameState = true;
                    waitingForOurTurn = true;  // ignore next player's GameState(START1A) message seen before Turn(nextPN)
                    pause(3);
                }

                expectSTART1B = false;
            }
            break;

            case SOCGame.START2A:
            {
                if ((! waitingForOurTurn) && ourTurn && (! (expectPUTPIECE_FROM_START2A && (counter < 4000))))
                {
                	//START2A: save
//                	if(!saved){
//            			saveGame();
//                	}
                    final int secondSettleNode = decisionMaker.planSecondSettlement();
                    placeInitSettlement(decisionMaker.secondSettlement);

                    expectPUTPIECE_FROM_START2A = true;
                    counter = 0;
                    waitingForGameState = true;
                }

                expectSTART2A = false;
            }
            break;

            case SOCGame.START2B:
            {
                if ((! waitingForOurTurn) && ourTurn && (! (expectPUTPIECE_FROM_START2B && (counter < 4000))))
                {
                	//START2B: save
//                	if(!saved){
//                		saveGame();
//                	}
                    planAndPlaceInitRoad();

                    expectPUTPIECE_FROM_START2B = true;
                    counter = 0;
                    waitingForGameState = true;
                    waitingForOurTurn = true;  // ignore next player's GameState(START2A) message seen before Turn(nextPN)
                    pause(3);
                }

                expectSTART2B = false;
            }
            break;

        case SOCGame.START3A:
            {
                if ((! waitingForOurTurn) && ourTurn && (! (expectPUTPIECE_FROM_START3A && (counter < 4000))))
                {
                    final int secondSettleNode = openingBuildStrategy.planSecondSettlement();  // TODO planThirdSettlement
                    placeInitSettlement(secondSettleNode);

                    expectPUTPIECE_FROM_START3A = true;
                    counter = 0;
                    waitingForGameState = true;
                }

                expectSTART3A = false;
            }
            break;

        case SOCGame.START3B:
            {
                if ((! waitingForOurTurn) && ourTurn && (! (expectPUTPIECE_FROM_START3B && (counter < 4000))))
                {
                    planAndPlaceInitRoad();

                    expectPUTPIECE_FROM_START3B = true;
                    counter = 0;
                    waitingForGameState = true;
                    waitingForOurTurn = true;  // ignore next player's GameState(START3A) message seen before Turn(nextPN)
                    pause(1500);
                }

                expectSTART3B = false;
            }
            break;

        }
    }

    /**
     * Play a Knight card.
     * In scenario {@link SOCGameOptionSet#K_SC_PIRI _SC_PIRI}, play a "Convert to Warship" card.
     * Sets {@link #expectPLACING_ROBBER}, {@link #waitingForGameState}.
     * Calls {@link SOCRobotClient#playDevCard(SOCGame, int) client.playDevCard}({@link SOCDevCardConstants#KNIGHT KNIGHT}).
     *<P>
     * In scenario {@code _SC_PIRI}, the server response messages are different, but we
     * still use those two flag fields; see {@link #expectPLACING_ROBBER} javadoc.
     *
     * @see #playKnightCardIfShould()
     * @since 2.0.00
     */
    private void playKnightCard()
    {
        expectPLACING_ROBBER = true;
        waitingForGameState = true;
        counter = 0;
        client.playDevCard(game, SOCDevCardConstants.KNIGHT);
        pause(1500);
    }

    /**
     * On our turn, ask client to roll dice or play a knight;
     * on other turns, update flags to expect dice result.
     *<P>
     * Call when gameState {@link SOCGame#ROLL_OR_CARD} && ! {@link #waitingForGameState}.
     *<P>
     * Clears {@link #expectROLL_OR_CARD} to false.
     * Sets either {@link #expectDICERESULT}, or {@link #expectPLACING_ROBBER} and {@link #waitingForGameState}.
     *<P>
     * In scenario {@code _SC_PIRI}, don't play a Knight card before dice roll, because the scenario has
     * no robber: Playing before the roll won't un-block any of our resource hexes, and it might put us
     * over 7 resources.
     *
     * @see #playKnightCardIfShould()
     * @since 1.1.08
     */
    protected void rollOrPlayKnightOrExpectDice()
    {
        expectROLL_OR_CARD = false;

        if ((! waitingForOurTurn) && ourTurn)
        {
            if (!expectPLAY1 && !expectDISCARD && !expectPLACING_ROBBER && ! (expectDICERESULT && (counter < 4000)))
            {
            	
            	//collect data at each turn start, before making the first action of rolling or playing knight
            	if(this.getClass().getName().equals(StacRobotBrain.class.getName())){
            		StacRobotBrain br = (StacRobotBrain) this;
            		if(Simulation.dbh!= null && Simulation.dbh.isConnected()){
            			//active game with at least one of our piece on the board and our turn
                    	if(game.getGameState()>=SOCGame.START1A && game.getGameState()<=SOCGame.OVER 
                    			&& game.getPlayer(ourPlayerData.getPlayerNumber()).getPieces().size()>0 && game.getCurrentPlayerNumber()==ourPlayerData.getPlayerNumber()){
                    		//check if vector has changed since last computed to avoid duplicates
                    		int[] vector = br.createStateVector();
                    		if(stateVector==null || !Arrays.equals(stateVector, vector)){
                    			Simulation.writeToDb(vector, br.calculateStateValue());
                    			stateVector = vector;
                    		}
                    	}
                	}
            	}
            	
                /**
                 * if we have a knight card and the robber
                 * is on one of our numbers, play the knight card
                 */
            	if (ourPlayerData.getInventory().hasPlayable(SOCDevCardConstants.KNIGHT)
                    && (rejectedPlayDevCardType != SOCDevCardConstants.KNIGHT)
                    && (! game.isGameOptionSet(SOCGameOptionSet.K_SC_PIRI))  // scenario has no robber; wait until after roll
                    && (! ourPlayerData.getNumbers().hasNoResourcesForHex(game.getBoard().getRobberHex()))
                    && decisionMaker.shouldPlayKnight(false))
                {
                	//PLAYB save, but this robot assumes we should play a knight and we have one
//                	if(!saved){
//                		expectPLAY = true;
//                		saveGame();
//                		expectPLAY = false;
//                	}
            		// TODO: I assume the hasplayedcard flag is set when we handle the message?
            		playKnightCard();  // sets expectPLACING_ROBBER, waitingForGameState
                }
                else
                {
                	//PLAYA save
//                	if(!saved){
//                		expectPLAY = true;
//                		saveGame();
//                		expectPLAY = false;
//                	}
                    expectDICERESULT = true;
                    counter = 0;

                    //D.ebugPrintln("!!! ROLLING DICE !!!");
                    client.rollDice(game);
                }
            }
        }
        else
        {
            /**
             * not our turn
             */
            expectDICERESULT = true;
        }
    }

    /**
     * Either ask to build a planned piece, or use trading or development cards to get resources to build it.
     * Examines {@link #buildingPlan} for the next piece wanted.
     * Sets {@link #whatWeWantToBuild} by calling {@link #buildRequestPlannedPiece()}
     * or using a Road Building dev card.
     *<P>
     * If we need resources and we can't get them through the robber,
     * the {@link SOCDevCardConstants#ROADS Road Building} or
     * {@link SOCDevCardConstants#MONO Monopoly} or
     * {@link SOCDevCardConstants#DISC Discovery} development cards,
     * then trades with the bank ({@link #tradeWithBank(SOCBuildPlan)})
     * or with other players ({@link #makeOffer(SOCBuildPlan)}).
     *<P>
     * Call when these conditions are all true:
     * <UL>
     *<LI> {@link #ourTurn}
     *<LI> {@link #planBuilding()} already called
     *<LI> ! {@link #buildingPlan}.empty()
     *<LI> gameState {@link SOCGame#PLAY1} or {@link SOCGame#SPECIAL_BUILDING}
     *<LI> <tt>waitingFor...</tt> flags all false ({@link #waitingForGameState}, etc)
     *     except possibly {@link #waitingForSpecialBuild}
     *<LI> <tt>expect...</tt> flags all false ({@link #expectPLACING_ROAD}, etc)
     *<LI> ! {@link #waitingForOurTurn}
     *<LI> ! ({@link #expectROLL_OR_CARD} && (counter < 4000))
     *</UL>
     *<P>
     * May set any of these flags:
     * <UL>
     *<LI> {@link #waitingForGameState}, and {@link #expectWAITING_FOR_DISCOVERY} or {@link #expectWAITING_FOR_MONOPOLY}
     *<LI> {@link #waitingForTradeMsg} or {@link #waitingForTradeResponse} or {@link #doneTrading}
     *<LI> {@link #waitingForDevCard}, or {@link #waitingForGameState} and {@link #expectPLACING_SETTLEMENT} (etc).
     *<LI> {@link #waitingForPickSpecialItem}
     *<LI> Scenario actions such as {@link #waitingForSC_PIRI_FortressRequest}
     *</UL>
     *<P>
     * In a future iteration of the run() loop with the expected {@code PLACING_} state, the
     * bot will build {@link #whatWeWantToBuild} by calling {@link #placeIfExpectPlacing()}.
     *
     * @param plan the building plan to follow when deciding, as this may change in the memory and is risky to call it repeatedly
     * @since 1.1.08
     * @throws IllegalStateException  if {@code plan}{@link Stack#isEmpty() .isEmpty()}
     */
    protected void buildOrGetResourceByTradeOrCard(BP plan)
        throws IllegalStateException
    {
        if (buildingPlan.isEmpty())
            throw new IllegalStateException("buildingPlan empty when called");

    	//PLAY1 save
//    	if(!saved){
//    		saveGame();
//    	}
        D.ebugPrintlnINFO("TRY BUILDING/TRADING - " + ourPlayerData.getName() + " -- plan: " + plan);
        /**
         * If we're in SPECIAL_BUILDING (not PLAY1),
         * can't trade or play development cards.
         */
        final boolean gameStatePLAY1 = (game.getGameState() == SOCGame.PLAY1);

        /**
         * check to see if this is a Road Building plan
         */
        boolean roadBuildingPlan = false;
        // TODO handle ships here

        if (gameStatePLAY1
            && (! ourPlayerData.hasPlayedDevCard())
            && (ourPlayerData.getNumPieces(SOCPlayingPiece.ROAD) >= 2)
            && ourPlayerData.getInventory().hasPlayable(SOCDevCardConstants.ROADS)
            && (rejectedPlayDevCardType != SOCDevCardConstants.ROADS))
        {
        	// TODO: Move isRoadBuildingPlan into DM class.  The existing implementation is iffy because it assumes
        	//  the road building involves the first two steps - it may be that we want to build a road to allow a settlement,
        	//  and then another road to build towards our next settlement.  It may also be the case that it's worth playing the RB#
        	//  card even though we only really need one road - a most extreme example is where we only need one road to 
        	//  take LR and win the game - current implementation won't consider using a road building card for that.
        	// This would also allow us to rework the building plan interface, as this is the only place that looks ahead in the stack 
        	//  with the current implementation.
        	
            //D.ebugPrintln("** Checking for Road Building Plan **");
            SOCPossiblePiece topPiece = plan.getPlannedPiece(0); 

            //D.ebugPrintln("$ POPPED "+topPiece);
            if ((topPiece != null) && (topPiece.instanceof SOCPossibleRoad) && (plan.getPlanDepth() > 1))
            {
                SOCPossiblePiece secondPiece = plan.getPlannedPiece(1);

                //D.ebugPrintln("secondPiece="+secondPiece);
                if ((secondPiece != null) && (secondPiece instanceof SOCPossibleRoad))
                {
                    roadBuildingPlan = true;

                    // TODO for now, 2 coastal roads/ships are always built as roads, not ships;
                    // builds ships only if the 2 possible pieces are non-coastal ships
                    if ((topPiece instanceof SOCPossibleShip)
                        && (! ((SOCPossibleShip) topPiece).isCoastalRoadAndShip )
                        && (secondPiece instanceof SOCPossibleShip)
                        && (! ((SOCPossibleShip) secondPiece).isCoastalRoadAndShip ))
                        whatWeWantToBuild = new SOCShip(ourPlayerData, topPiece.getCoordinates(), null);
                    else
                        whatWeWantToBuild = new SOCRoad(ourPlayerData, topPiece.getCoordinates(), null);

                    if (! whatWeWantToBuild.equals(whatWeFailedToBuild))
                    {
                    	if (decisionMaker.shouldPlayRoadbuilding()) {
	                        waitingForGameState = true;
	                        counter = 0;
	                        expectPLACING_FREE_ROAD1 = true;
                                plan.advancePlan();  // consume topPiece
	
	                        //D.ebugPrintln("!! PLAYING ROAD BUILDING CARD");
	                        client.playDevCard(game, SOCDevCardConstants.ROADS);
                    	}
                    } else {
                        // We already tried to build this.
                        roadBuildingPlan = false;
                        cancelWrongPiecePlacementLocal(whatWeWantToBuild);
                        // cancel sets whatWeWantToBuild = null;
                    }
                }
            }
        }

        if (roadBuildingPlan)
        {
            return;  // <---- Early return: Road Building dev card ----
        }

        // Defer this - we want the ability for the negotiator to change plans on the fly based on results of trading
        //SOCPossiblePiece targetPiece = buildingPlan.getPlannedPiece(0);
        //SOCResourceSet targetResources = targetPiece.getResourcesToBuild();  // may be null

        //D.ebugPrintln("^^^ targetPiece = "+targetPiece);
        //D.ebugPrintln("^^^ ourResources = "+ourPlayerData.getResources());

        negotiator.setTargetPiece(ourPlayerNumber, plan);

        ///
        /// if we have a 2 free resources card and we need
        /// at least 2 resources, play the card
        ///
        if (gameStatePLAY1
            && (! ourPlayerData.hasPlayedDevCard())
            && ourPlayerData.getInventory().hasPlayable(SOCDevCardConstants.DISC)
            && (rejectedPlayDevCardType != SOCDevCardConstants.DISC)
            && decisionMaker.shouldPlayDiscovery())
        {                
            decisionMaker.chooseFreeResources(plan);

            ///
            /// play the card
            ///
            expectWAITING_FOR_DISCOVERY = true;
            waitingForGameState = true;
            counter = 0;
            client.playDevCard(game, SOCDevCardConstants.DISC);
            pause(3);                
        }

        if (! expectWAITING_FOR_DISCOVERY)
        {
            ///
            /// if we have a monopoly card, play it
            /// and take what there is most of
            ///
            if (gameStatePLAY1
                && (! ourPlayerData.hasPlayedDevCard())
                && (ourPlayerData.getInventory().hasPlayable(SOCDevCardConstants.MONO)
                && (rejectedPlayDevCardType != SOCDevCardConstants.MONO)
                && monopolyStrategy.decidePlayMonopoly())
            {
                ///
                /// play the card
                ///
            	// TODO: Consider the possibility of making trades and then playing monopoly (eg offer a 2 for 1 and then grab it back)
                expectWAITING_FOR_MONOPOLY = true;
                waitingForGameState = true;
                counter = 0;
                client.playDevCard(game, SOCDevCardConstants.MONO);
                pause(3);
            }

            if (! expectWAITING_FOR_MONOPOLY)
            {
                if (gameStatePLAY1 && (! doneTrading))
                { 
                //  Remove this condition - we may want to trade regardless of our current ability 
                //  to build.  Default implementation checks this anyway
                // && (!ourPlayerData.getResources().contains(targetResources)))
                
                    waitingForTradeResponse = false;

                    if (robotParameters.getTradeFlag() == 1
                    		&& !disableTrades)
                    {
                        D.ebugPrintlnINFO("TRY OFFERING");
                        makeOffer(plan);
                        // makeOffer will set waitingForTradeResponse or doneTrading.
                        // NB: With some implementations, makeOffer may instruct the brain to wait
                        // In that case, return from this function
                        if (isWaiting() ) {
                            return;
                        }
                    }
                }

                if (! isWaiting() && gameStatePLAY1 && ! waitingForTradeResponse && ! waitingForTradeMsg)
                {
                    /**
                     * trade with the bank/ports
                     */
                    D.ebugPrintlnINFO("TRY BANK TRADE");
                    if (tradeWithBank(plan))
                    {
                        counter = 0;
                        waitingForTradeMsg = true;
                        pause(3);
                    }
                }

                ///
                /// build if we can
                ///
                SOCPossiblePiece targetPiece = (SOCPossiblePiece) plan.getPlannedPiece(0);
                SOCResourceSet targetResources = SOCPlayingPiece.getResourcesToBuild(targetPiece.getType());
                if ((! isWaiting() && ! (waitingForTradeMsg || waitingForTradeResponse))
                    && ourPlayerData.getResources().contains(targetResources))
                {
                    // Remember that targetPiece == buildingPlan.peek().
                    // Calls buildingPlan.pop().
                    // Checks against whatWeFailedToBuild to see if server has rejected this already.
                    // Calls client.buyDevCard or client.buildRequest.
                    // Sets waitingForDevCard, or waitingForGameState and expectPLACING_SETTLEMENT (etc).
                    // Sets waitingForPickSpecialItem if target piece is SOCPossiblePickSpecialItem.

                    D.ebugPrintlnINFO("MAKE BUILD REQUEST - " + targetPiece);
                    buildRequestPlannedPiece(targetPiece, plan);
                }
            }
        }
    }

    /**
     * On our turn, server is expecting us to choose a placement location for a {@link SOCInventoryItem}.
     * Try to plan a location and send placement request command(s).
     *<P>
     * Call only when these conditions are all true:
     *<UL>
     * <LI> {@link #ourTurn} &amp;&amp; ! {@link #waitingForOurTurn}
     * <LI> game state {@link SOCGame#PLACING_INV_ITEM PLACING_INV_ITEM}
     * <LI> ! {@link #waitingForGameState}
     *</UL>
     * If the piece can be planned and placed, will set {@link #waitingForGameState}
     * and either {@link #expectPLAY1} or another expect flag, and send placement commands.
     * If nothing could be planned and placed, does not set {@link #waitingForGameState}.
     *
     * @throws IllegalStateException if called with {@link #waitingForGameState} true
     * @since 2.0.00
     */
    protected void planAndPlaceInvItem()
        throws IllegalStateException
    {
        if (waitingForGameState)
            throw new IllegalStateException();

        SOCInventoryItem itm = game.getPlacingItem();
        if (itm == null)
            return;  // in case of bugs; shouldn't happen in a consistent game

        if (game.isGameOptionSet(SOCGameOptionSet.K_SC_FTRI))
        {
            planAndPlaceInvItemPlacement_SC_FTRI(itm);
        } else {
            System.err.println
                ("L2720: Game " + game.getName() + " bot " + client.getNickname()
                 + ": No PLACING_INV_ITEM handler for scenario " + game.getGameOptionStringValue("SC"));

            // End turn? Probably cleaner to let server force-end it. So do nothing here.
            // TODO revisit that: Per PLACING_INV_ITEM javadoc:
            // "For some kinds of item, placement can be canceled by calling ga.cancelPlaceInventoryItem"
        }
    }

    /**
     * For scenario {@link SOCGameOptionSet#K_SC_FTRI _SC_FTRI}, try to plan a location and
     * send placement request command(s) for a "gift" trade port on the player's turn.
     *<P>
     * Calls {@link SOCPlayer#getPortMovePotentialLocations(boolean)}; this method is safe to call
     * when the player has nowhere to place.
     *<P>
     * Called from {@link #planAndPlaceInvItem()}, see that method for required conditions to call
     * and described brain-state results after calling.  Assumes caller has checked those conditions
     * and:
     *<UL>
     * <LI> {@link #ourTurn}
     * <LI> game state {@link SOCGame#PLACING_INV_ITEM PLACING_INV_ITEM} or {@link SOCGame#PLAY1 PLAY1}
     * <LI> ! {@link #waitingForGameState}
     *</UL>
     *
     * @param itm The gift port which must be placed; not {@code null}.
     *     Can be as prompted by server, or from player's {@link SOCInventoryItem}s.
     * @return true if {@code itm} was planned and/or placed
     * @since 2.0.00
     */
    private boolean planAndPlaceInvItemPlacement_SC_FTRI(final SOCInventoryItem itm)
    {
        if (itm == null)
            return false;
        if ((rejectedPlayInvItem != null) && (itm.itype == rejectedPlayInvItem.itype))
            return false;  // rbrain must plan something else instead

        List<Integer> edges = ourPlayerData.getPortMovePotentialLocations(true);
        if (edges.isEmpty())
        {
            // TODO any action to keep it moving?
            rejectedPlayInvItem = itm;  // don't re-plan same thing for next move this turn

            return false;  // <--- Early return: No choices despite gamestate ---
        }

        final int ptype = itm.itype;  // reminder: will be negative or 0
        waitingForGameState = true;
        counter = 0;

        if (game.getGameState() == SOCGame.PLACING_INV_ITEM)
        {
            // This is a first draft for overall functionality, not for the smartest placement strategy.
            // TODO smarter planning; consider settlement & dice number locations / hex types against
            // the port type from itm if not null; if null iterate inv items
            // but reject if chooses rejectedPlayInvItem again
            final int edge = edges.get(0).intValue();

            // Expected response from server: GAMESTATE(PLAY1) and
            // then SIMPLEREQUEST confirming placement location,
            // or SIMPLEREQUEST rejecting it; TODO don't plan further building
            // until that's seen (new flag field?) because will need to recalc
            // building speed estimates (BSEs) with the new port.

            expectPLAY1 = true;
            client.simpleRequest(game, ourPlayerNumber, SOCSimpleRequest.TRADE_PORT_PLACE, edge, 0);
        } else {
            // State PLAY1; assume inv is from inventory

            // Expected response from server: GAMESTATE(PLACING_INV_ITEM).
            // If client's request is rejected because nowhere to place right now,
            // will respond with SOCInventoryItemAction(CANNOT_PLAY)
            // and rbrain will clear expectPLACING_INV_ITEM.

            expectPLACING_INV_ITEM = true;
            client.playInventoryItem(game, ourPlayerNumber, ptype);
        }

        pause(1000);
        return true;
    }

    /**
     * Handle a PUTPIECE for this game, by updating game data.
     * For initial roads, also track their initial settlement in SOCPlayerTracker.
     * In general, most tracking is done a bit later in {@link #handlePUTPIECE_updateTrackers(int, int, int)}.
     * @since 1.1.08
     */
    @SuppressWarnings("fallthrough")
    protected void handlePUTPIECE_updateGameData(SOCPutPiece mes)
    {
        switch (mes.getPieceType())
        {
        case SOCPlayingPiece.SHIP:  // fall through to ROAD
        case SOCPlayingPiece.ROAD:

            if (game.isInitialPlacement())  // START1B, START2B, START3B
            {
                //
                // Before processing this road/ship, track the settlement that goes with it.
                // This was deferred until road placement, in case a human player decides
                // to cancel their settlement and place it elsewhere.
                //
                SOCPlayerTracker tr = playerTrackers[mes.getPlayerNumber()];
                SOCSettlement se = tr.getPendingInitSettlement();
                if (se != null)
                    trackNewSettlement(se, false);
            }
            // fall through to default

        default:
            SOCDisplaylessPlayerClient.handlePUTPIECE(mes, game);
            break;
        }
    }

    /**
     * Handle a CANCELBUILDREQUEST for this game.
     *<P>
     *<b> During game startup</b> (START1B, START2B, or START3B): <BR>
     *    When sent from server to client, CANCELBUILDREQUEST means the current
     *    player wants to undo the placement of their initial settlement.
     *    This handler method calls {@link SOCGame#undoPutInitSettlement(SOCPlayingPiece)}
     *    and {@link SOCPlayerTracker#setPendingInitSettlement(SOCSettlement) tracker.setPendingInitSettlement(null)}.
     *<P>
     *<b> During piece placement</b> (PLACING_ROAD, PLACING_CITY, PLACING_SETTLEMENT,
     *                         PLACING_FREE_ROAD1, or PLACING_FREE_ROAD2): <BR>
     *    When sent from server to client, CANCELBUILDREQUEST means the player
     *    has sent an illegal PUTPIECE (bad building location).
     *    Humans can probably decide a better place to put their road,
     *    but robots must cancel the build request and decide on a new plan.
     *
     * @since 1.1.08
     */
    protected void handleCANCELBUILDREQUEST(SOCCancelBuildRequest mes)
    {
        final int gstate = game.getGameState();
        switch (gstate)
        {
        case SOCGame.START1A:
        case SOCGame.START2A:
        case SOCGame.START3A:
            if (ourTurn)
            {
                cancelWrongPiecePlacement(mes);
            }
            break;

        case SOCGame.START1B:
        case SOCGame.START2B:
        case SOCGame.START3B:
            if (ourTurn)
            {
                cancelWrongPiecePlacement(mes);
            }
            else
            {
                //
                // Human player placed, then cancelled placement
                // (assume mes.getPieceType() == SOCPlayingPiece.SETTLEMENT).
                // Our robot wouldn't do that, and if it's ourTurn,
                // the cancel happens only if we try an illegal placement.
                //
                final int pnum = game.getCurrentPlayerNumber();
                SOCPlayer pl = game.getPlayer(pnum);
                SOCSettlement pp = new SOCSettlement(pl, pl.getLastSettlementCoord(), null);
                game.undoPutInitSettlement(pp);
                //
                // "forget" to track this cancelled initial settlement.
                // Wait for human player to place a new one.
                //
                SOCPlayerTracker tr = playerTrackers[pnum];
                tr.setPendingInitSettlement(null);
            }
            break;

        case SOCGame.PLAY1:  // asked to build, hasn't given location yet -> resources
        case SOCGame.PLACING_ROAD:        // has given location -> is bad location
        case SOCGame.PLACING_SETTLEMENT:
        case SOCGame.PLACING_CITY:
        case SOCGame.PLACING_SHIP:
        case SOCGame.PLACING_FREE_ROAD1:  // JM TODO how to break out?
        case SOCGame.PLACING_FREE_ROAD2:  // JM TODO how to break out?
        case SOCGame.SPECIAL_BUILDING:
            //
            // We've asked for an illegal piece placement.
            // (Must be a bug.) Cancel and invalidate this
            // planned piece, make a new plan.
            //
            // Can also happen in special building, if another
            // player has placed since we requested special building.
            // If our PUTPIECE request is denied, server sends us
            // CANCELBUILDREQUEST.  We need to ask to cancel the
            // placement, and also set variables to end our SBP turn.
            //
            cancelWrongPiecePlacement(mes);
            break;

        default:
            if (game.isSpecialBuilding())
            {
                cancelWrongPiecePlacement(mes);
            } else {
                // Should not occur
                System.err.println
                    ("L2521 SOCRobotBrain: " + client.getNickname() + ": Unhandled CANCELBUILDREQUEST at state " + gstate);
            }

        }  // switch (gameState)
    }

    /**
     * Note that a player has replied to our offer, or we've accepted another player's offer.
     * Determine whether to keep waiting for responses, and update negotiator appropriately.
     * If {@code accepted}, also clears {@link #waitingForTradeResponse}
     * by calling {@link #clearTradingFlags(boolean, boolean)}.
     *
     * @param playerNum  Player number: The other player accepting or rejecting our offer,
     *     or {@link #ourPlayerNumber} if called for accepting another player's offer
     * @param accept  True if offer was accepted, false if rejected
     * @see #tradeStopWaitingClearOffer()
     */
    protected void handleTradeResponse(int playerNum, boolean accept) {
        waitingForTradeResponsePlayer[playerNum] = false;
        tradeAccepted |= accept;
        
        boolean everyoneResponded = true,
            allHumansRejected = (tradeResponseTimeoutSec > TRADE_RESPONSE_TIMEOUT_SEC_BOTS_ONLY);
        D.ebugPrintlnINFO("ourPlayerData.getCurrentOffer() = " + ourPlayerData.getCurrentOffer());

        for (int i = 0; i < game.maxPlayers; i++)
        {
            D.ebugPrintlnINFO("waiting for Responses[" + i + "]=" + waitingForTradeResponsePlayer[i]);

            if (waitingForTradeResponsePlayer[i]) {
                everyoneResponded = false;
                if (! game.getPlayer(pn).isRobot())
                    allHumansRejected = false;
                break;
            }
        }

        D.ebugPrintlnINFO("everyoneResponded=" + everyoneResponded);

        if (everyoneResponded)
        {
            if (! tradeAccepted) {
                negotiator.addToOffersMade(ourPlayerData.getCurrentOffer());
            }
            client.clearOffer(game);
            clearTradingFlags(false, true);
        }
        else if (allHumansRejected)
        {
            // can now shorten timeout
            tradeResponseTimeoutSec = TRADE_RESPONSE_TIMEOUT_SEC_BOTS_ONLY;
        }
    }
    
    /**
     * Handle a MAKEOFFER for this game.
     * if another player makes an offer, that's the
     * same as a rejection from them, but still wants to deal.
     * Call {@link #considerOffer(SOCTradeOffer)}, and if
     * we accept, clear our {@link #buildingPlan} so we'll replan it.
     * Ignore our own MAKEOFFERs echoed from server
     * and "not allowed" replies from server ({@link SOCTradeOffer#getFrom()} &lt; 0).
     * Call {@link SOCRobotNegotiator#recordResourcesFromOffer(SOCTradeOffer)}.
     * @since 1.1.08
     * TODO: Move logic of follow up into the negotiator
     */
    protected void handleMAKEOFFER(SOCMakeOffer mes)
    {
        SOCTradeOffer offer = mes.getOffer();
        final int fromPN = offer.getFrom();
        if (fromPN < 0)
        {
            return;  // <---- Ignore "not allowed" from server ----
        }

        final SOCPlayer offeredFromPlayer = game.getPlayer(fromPN);
        offeredFromPlayer.setCurrentOffer(offer);

        if (fromPN == ourPlayerNumber)
        {
            return;  // <---- Ignore our own offers ----
        }

        negotiator.recordResourcesFromOffer(offer);
        
        if (waitingForTradeResponse)
        {
            handleTradeResponse(fromPN, false);            
        }

        ///
        /// consider the offer
        ///
        int ourResponseToOffer = considerOffer(offer);

        D.ebugPrintlnINFO("%%% ourResponseToOffer = " + ourResponseToOffer);

        if (ourResponseToOffer < 0)
            return;  // <--- Early return: SOCRobotNegotiator.IGNORE_OFFER ---

        // Before pausing, note current offer and turn.
        // If that game data changes during the pause, we'll need to reconsider the current offer.
        // While brain thread is paused, robot client's message thread is still running
        // and will update game data if conditions change.

        final long offeredAt = offeredFromPlayer.getCurrentOfferTime();
        final int currentPN = game.getCurrentPlayerNumber();

        /* This needs to instead use the specifiable relative pause framework.
         * int delayLength = Math.abs(rand.nextInt() % 500) + 3500;
        if (gameIs6Player && ! waitingForTradeResponse)
        {
            delayLength *= 2;  // usually, pause is half-length in 6-player
        }*/
        pause(1500); 

        // See if trade conditions still apply after pause;
        // reconsider if needed

        if (currentPN != game.getCurrentPlayerNumber())
        {
            return;  // <--- new turn; will react to newly queued messages to reset brain fields for new turn ---
        }

        if (offeredAt != offeredFromPlayer.getCurrentOfferTime())
        {
            offer = offeredFromPlayer.getCurrentOffer();
            if ((offer == null) || ! offer.getTo()[ourPlayerNumber])
            {
                return;  // <--- nothing offered to us now ---
            }

            ourResponseToOffer = considerOffer(offer);
        }

        switch (ourResponseToOffer)
        {
            case SOCRobotNegotiator.ACCEPT_OFFER:
                {
                    // since response is ACCEPT_OFFER, offer validity has already been checked

                    boolean[] offeredTo = offer.getTo();

                    // pause a bit if this was offered to at least one human player.
                    for (int i = 0; i < offeredTo.length; i++)
                    {
                        if (offeredTo[i] && ! game.getPlayer(i).isRobot())
                        {
                            // offered to at least one human player; wait for the human brain to catch up
                            // TODO: figure out how to interrupt the Thread.sleep once all humans have responded
                            //  to the trade offer if it's faster than the pause time.

                            pause((BOTS_PAUSE_FOR_HUMAN_TRADE * 1000 - 1500);
                                // already waited 1.5 seconds: see pause above
                            break;      // one wait for all humans is enough
                        }
                    }

                    client.acceptOffer(game, fromPN);

                    // We need to process the server's notification of this trade before proceeding
                    waitingForTradeMsg = true;

                    /// clear our building plan, so that we replan
                    resetBuildingPlan();
                    negotiator.setTargetPiece(ourPlayerData.getPlayerNumber(), null);
                }

                break;

            case SOCRobotNegotiator.REJECT_OFFER:
                if (! waitingForTradeResponse)
                    client.rejectOffer(game);

                break;

            case SOCRobotNegotiator.COUNTER_OFFER:
                if (! makeCounterOffer(offer))
                    client.rejectOffer(game);

                break;

            case SOCRobotNegotiator.COMPLETE_OFFER:
                if (! makeCompletedOffer(offer))
                    client.rejectOffer(game);

                break;
        }
    }

    /**
     * Handle a REJECTOFFER for this game.
     * watch rejections of other players' offers, and of our offers.
     * If everyone's rejected our offer, clear {@link #waitingForTradeResponse}.
     * @since 1.1.08
     */
    protected void handleREJECTOFFER(SOCRejectOffer mes)
    {
        int rejector = mes.getPlayerNumber();

        if (waitingForTradeResponse)
        {
            handleTradeResponse(mes.getPlayerNumber(), false);  // clear trading flags
            // If this is false, it means the rejected trade was accepted by another player.
            //  Since it has been cleared from the data object, it unfortunately cannot be
            //  passed to the negotiator.
            //  TODO: Rework so that we have access to this?
            negotiator.recordResourcesFromReject(rejector);
        }
        else
        {
            negotiator.recordResourcesFromRejectAlt(rejector);
        }
    }

    /**
     * Handle a DEVCARDACTION for this game.
     * No brain-specific action.
     * Ignores messages where {@link SOCDevCardAction#getCardTypes()} != {@code null}.
     *<P>
     * Before v2.0.00 this method was {@code handleDEVCARD}.
     *
     * @since 1.1.08
     */
    protected void handleDEVCARDACTION(SOCDevCardAction mes)
    {
        if (mes.getCardTypes() != null)
            return;  // <--- ignore: bots don't care about game-end VP card reveals ---

        final int cardType = mes.getCardType();
        SOCPlayer pl = game.getPlayer(mes.getPlayerNumber());
        SOCInventory cardsInv = pl.getInventory();

        switch (mes.getAction())
        {
        case SOCDevCardAction.DRAW:
            cardsInv.addDevCard(1, SOCInventory.NEW, cardType);
            break;

        case SOCDevCardAction.PLAY:
            plCards.removeDevCard(SOCInventory.OLD, cardType);
            pl.updateDevCardsPlayed(cardType);
            break;

        case SOCDevCardAction.ADD_OLD:
            cardsInv.addDevCard(1, SOCInventory.OLD, cardType);
            break;

        case SOCDevCardAction.ADD_NEW:
            cardsInv.addDevCard(1, SOCInventory.NEW, cardType);
            break;
        }
    }

    /**
     * Handle a PUTPIECE for this game, by updating {@link SOCPlayerTracker}s.
     * Also handles the "move piece to here" part of MOVEPIECE.
     *<P>
     * For initial placement of our own pieces, this method also checks
     * and clears expectPUTPIECE_FROM_START1A, and sets expectSTART1B, etc.
     * The final initial putpiece clears expectPUTPIECE_FROM_START2B and sets expectROLL_OR_CARD.
     * As part of the PUTPIECE request, brain set those expectPUTPIECE flags in {@link #placeIfExpectPlacing()}.
     *<P>
     * For initial settlements, won't track here:
     * Delay tracking until the corresponding road is placed,
     * in {@link #handlePUTPIECE_updateGameData(SOCPutPiece)}.
     * This prevents the need for tracker "undo" work if a human
     * player changes their mind on where to place the settlement.
     *
     * @param pn  Piece's player number
     * @param coord  Piece coordinate
     * @param pieceType  Piece type, as in {@link SOCPlayingPiece#SETTLEMENT}
     * @since 1.1.08
     */
    public void handlePUTPIECE_updateTrackers(final int pn, final int coord, final int pieceType)
    {
        switch (pieceType)
        {
        case SOCPlayingPiece.ROAD:

            SOCRoad newRoad = new SOCRoad(game.getPlayer(pn), coord, null);
            trackNewRoadOrShip(newRoad, false);
            break;

        case SOCPlayingPiece.SETTLEMENT:

            SOCPlayer newSettlementPl = game.getPlayer(pn);
            SOCSettlement newSettlement = new SOCSettlement(newSettlementPl, coord, null);
            if ((game.getGameState() == SOCGame.START1B) || (game.getGameState() == SOCGame.START2B)
                || (game.getGameState() == SOCGame.START3B))
            {
                // Track it soon, after the road is placed
                // (in handlePUTPIECE_updateGameData)
                // but not yet, in case player cancels placement.
                SOCPlayerTracker tr = playerTrackers[newSettlementPl.getPlayerNumber()];
                tr.setPendingInitSettlement(newSettlement);
            }
            else
            {
                // Track it now
                trackNewSettlement(newSettlement, false);
            }
            break;

        case SOCPlayingPiece.CITY:

            SOCCity newCity = new SOCCity(game.getPlayer(pn), coord, null);
            trackNewCity(newCity, false);
            break;

        case SOCPlayingPiece.SHIP:

            SOCShip newShip = new SOCShip(game.getPlayer(pn), coord, null);
            trackNewRoadOrShip(newShip, false);
            break;

        case SOCPlayingPiece.VILLAGE:
            return;  // <--- Early return: Piece is part of board initial layout, not tracked player info ---

        }

        if (D.ebugOn)
        {
            SOCPlayerTracker.playerTrackersDebug(playerTrackers);
        }

        if (pn != ourPlayerNumber)
        {
            return;  // <---- Not our piece ----
        }

        /**
         * Update expect-vars during initial placement of our pieces.
         */

        if (expectPUTPIECE_FROM_START1A && (pieceType == SOCPlayingPiece.SETTLEMENT)
            && (coord == ourPlayerData.getLastSettlementCoord()))
        {
            expectPUTPIECE_FROM_START1A = false;
            expectSTART1B = true;
        }

        if (expectPUTPIECE_FROM_START1B
            && ((pieceType == SOCPlayingPiece.ROAD) || (pieceType == SOCPlayingPiece.SHIP))
            && (coord == ourPlayerData.getLastRoadCoord()))
        {
            expectPUTPIECE_FROM_START1B = false;
            expectSTART2A = true;
        }

        if (expectPUTPIECE_FROM_START2A && (pieceType == SOCPlayingPiece.SETTLEMENT)
            && (coord == ourPlayerData.getLastSettlementCoord()))
        {
            expectPUTPIECE_FROM_START2A = false;
            expectSTART2B = true;
        }

        if (expectPUTPIECE_FROM_START2B
            && ((pieceType == SOCPlayingPiece.ROAD) || (pieceType == SOCPlayingPiece.SHIP))
            && (coord == ourPlayerData.getLastRoadCoord()))
        {
            expectPUTPIECE_FROM_START2B = false;
            if (! game.isGameOptionSet(SOCGameOptionSet.K_SC_3IP))
                expectROLL_OR_CARD = true;    // wait for regular game play to start; other players might still place first
            else
                expectSTART3A = true;
        }

        if (expectPUTPIECE_FROM_START3A
            && (pieceType == SOCPlayingPiece.SETTLEMENT)
            && (coord == ourPlayerData.getLastSettlementCoord()))
        {
            expectPUTPIECE_FROM_START3A = false;
            expectSTART3B = true;
        }

        if (expectPUTPIECE_FROM_START3B
            && ((pieceType == SOCPlayingPiece.ROAD) || (pieceType == SOCPlayingPiece.SHIP))
            && (coord == ourPlayerData.getLastRoadCoord()))
        {
            expectPUTPIECE_FROM_START3B = false;
            expectROLL_OR_CARD = true;
        }

    }

    /**
     * Have the client ask to build this piece, unless we've already
     * been told by the server to not build it.
     * Calls {@link #buildingPlan}.{@link SOCBuildPlan#advancePlan() advancePlan()}.
     * Sets {@link #whatWeWantToBuild}, {@link #waitingForDevCard},
     * or {@link #waitingForPickSpecialItem}.
     * Called from {@link #buildOrGetResourceByTradeOrCard()}.
     *<P>
     * Checks against {@link #whatWeFailedToBuild} to see if server has rejected this already.
     * Calls <tt>client.buyDevCard()</tt> or <tt>client.buildRequest()</tt>.
     * Sets {@link #waitingForDevCard} or {@link #waitingForPickSpecialItem},
     * or sets {@link #waitingForGameState} and a flag like {@link #expectPLACING_SETTLEMENT} (etc).
     *<P>
     * Preconditions: Call only when:
     *<UL>
     * <LI> Gamestate is {@link SOCGame#PLAY1} or {@link SOCGame#SPECIAL_BUILDING}
     * <LI> <tt>! ({@link #waitingForTradeMsg} || {@link #waitingForTradeResponse})</tt>
     * <LI> ourPlayerData.getResources().{@link SOCResourceSet#contains(soc.game.ResourceSet) contains}(targetPieceResources)
     *</UL>
     *
     * @param targetPiece  This should be the top piece of {@link #buildingPlan}.
     * @param plan 
     * @see #placeIfExpectPlacing()
     * @since 1.1.08
     */
    protected void buildRequestPlannedPiece(SOCPossiblePiece targetPiece, BP plan)
    {
        D.ebugPrintlnINFO("$ POPPED " + targetPiece);
        lastMove = targetPiece;
        currentDRecorder = (currentDRecorder + 1) % 2;
        negotiator.setTargetPiece(ourPlayerNumber, plan);

        plan.advancePlan();

        switch (targetPiece.getType())
        {
        case SOCPossiblePiece.CARD:
            client.buyDevCard(game);
            waitingForDevCard = true;

            break;

        case SOCPossiblePiece.ROAD:
            waitingForGameState = true;
            counter = 0;
            expectPLACING_ROAD = true;
            whatWeWantToBuild = new SOCRoad(ourPlayerData, targetPiece.getCoordinates(), null);
            if (! whatWeWantToBuild.equals(whatWeFailedToBuild))
            {
                D.ebugPrintlnINFO("!!! BUILD REQUEST FOR A ROAD AT " + Integer.toHexString(targetPiece.getCoordinates()) + " !!!");
                client.buildRequest(game, SOCPlayingPiece.ROAD);
            } else {
                // We already tried to build this.
                cancelWrongPiecePlacementLocal(whatWeWantToBuild);
                // cancel sets whatWeWantToBuild = null;
            }

            break;

        case SOCPlayingPiece.SETTLEMENT:
            waitingForGameState = true;
            counter = 0;
            expectPLACING_SETTLEMENT = true;
            whatWeWantToBuild = new SOCSettlement(ourPlayerData, targetPiece.getCoordinates(), null);
            if (! whatWeWantToBuild.equals(whatWeFailedToBuild))
            {
                D.ebugPrintlnINFO("!!! BUILD REQUEST FOR A SETTLEMENT " + Integer.toHexString(targetPiece.getCoordinates()) + " !!!");
                client.buildRequest(game, SOCPlayingPiece.SETTLEMENT);
            } else {
                // We already tried to build this.
                cancelWrongPiecePlacementLocal(whatWeWantToBuild);
                // cancel sets whatWeWantToBuild = null;
            }

            break;

        case SOCPlayingPiece.CITY:
            waitingForGameState = true;
            counter = 0;
            expectPLACING_CITY = true;
            whatWeWantToBuild = new SOCCity(ourPlayerData, targetPiece.getCoordinates(), null);
            if (! whatWeWantToBuild.equals(whatWeFailedToBuild))
            {
                D.ebugPrintlnINFO("!!! BUILD REQUEST FOR A CITY " + Integer.toHexString(targetPiece.getCoordinates()) + " !!!");
                client.buildRequest(game, SOCPlayingPiece.CITY);
            } else {
                // We already tried to build this.
                cancelWrongPiecePlacementLocal(whatWeWantToBuild);
                // cancel sets whatWeWantToBuild = null;
            }

            break;

        case SOCPlayingPiece.SHIP:
            waitingForGameState = true;
            counter = 0;
            expectPLACING_SHIP = true;
            whatWeWantToBuild = new SOCShip(ourPlayerData, targetPiece.getCoordinates(), null);
            if (! whatWeWantToBuild.equals(whatWeFailedToBuild))
            {
                /*
                System.err.println("L2733: " + ourPlayerData.getName() + ": !!! BUILD REQUEST FOR A SHIP AT "
                    + Integer.toHexString(targetPiece.getCoordinates()) + " !!!");
                D.ebugPrintlnINFO("!!! BUILD REQUEST FOR A SHIP AT " + Integer.toHexString(targetPiece.getCoordinates()) + " !!!");
                 */
                client.buildRequest(game, SOCPlayingPiece.SHIP);
            } else {
                // We already tried to build this.
                cancelWrongPiecePlacementLocal(whatWeWantToBuild);
                // cancel sets whatWeWantToBuild = null;
            }

            break;

        case SOCPossiblePiece.PICK_SPECIAL:
            {
                final SOCPossiblePickSpecialItem psi = (SOCPossiblePickSpecialItem) targetPiece;
                waitingForPickSpecialItem = psi.typeKey;
                whatWeWantToBuild = null;  // targetPiece isn't a SOCPlayingPiece
                counter = 0;

                client.pickSpecialItem(game, psi.typeKey, psi.gi, psi.pi);
            }
            break;

        default:
            // shouldn't occur: print for debugging
            System.err.println
                (ourPlayerData.getName() + ": buildRequestPlannedPiece: Unknown piece type " + targetPiece.getType());
        }
    }

    /**
     * Plan the next building plan and target.
     * Should be called from {@link #run()} under these conditions: <BR>
     * ( !expectPLACING_ROBBER && buildingPlan.empty() && (ourPlayerData.getResources().getTotal() > 1)
     * && (failedBuildingAttempts < MAX_DENIED_BUILDING_PER_TURN))
     *<P>
     * Sets these fields and makes these calls:
     *<UL>
     * <LI> {@link SOCRobotDM#planStuff(int) SOCRobotDM.planStuff}
     *      ({@link SOCRobotDM#FAST_STRATEGY FAST_STRATEGY} or {@link SOCRobotDM#SMART_STRATEGY SMART_STRATEGY})
     * <LI> {@link #buildingPlan}
     * <LI> {@link #lastTarget}
     * <LI> {@link SOCRobotNegotiator#setTargetPiece(int, SOCPossiblePiece)}
     *</UL>
     *
     * @since 1.1.08
     * TODO: Parallel plans - how?
     */
    protected void planBuilding()
    {
        decisionMaker.planStuff();
        BP plan = getBuildingPlan();
        if (! plan.isEmpty())
        {
            // Looks like this is only for logging, so no problem if it's not our actual target
        	lastTarget =  plan.getPlannedPiece(0);
        	
            negotiator.setTargetPiece(ourPlayerData.getPlayerNumber(), plan);
        }
    }

    /**
     * Handle a PLAYERELEMENTS for this game.
     * See {@link #handlePLAYERELEMENT(SOCPlayer, int, int, PEType, int)} for actions taken.
     * @since 2.0.00
     */
    protected void handlePLAYERELEMENTS(SOCPlayerElements mes)
    {
        final int pn = mes.getPlayerNumber();
        final SOCPlayer pl = (pn != -1) ? game.getPlayer(pn) : null;
        final int action = mes.getAction();
        final int[] etypes = mes.getElementTypes(), amounts = mes.getAmounts();

        for (int i = 0; i < etypes.length; ++i)
            handlePLAYERELEMENT(pl, pn, action, PEType.valueOf(etypes[i]), amounts[i]);
    }

    /**
     * Handle a PLAYERELEMENT for this game.
     * See {@link #handlePLAYERELEMENT(SOCPlayer, int, int, PEType, int)} for actions taken.
     * @since 1.1.08
     */
    protected void handlePLAYERELEMENT(SOCPlayerElement mes)
    {
        final int pn = mes.getPlayerNumber();
        final int action = mes.getAction(), amount = mes.getAmount();
        final PEType etype = PEType.valueOf(mes.getElementType());

        handlePLAYERELEMENT(null, pn, action, etype, amount);
    }

    /**
     * Handle a player information update from a {@link SOCPlayerElement} or {@link SOCPlayerElements} message:
     * Update a player's amount of a resource or a building type.
     *<P>
     * If this during the {@link SOCGame#ROLL_OR_CARD} state, then update the
     * {@link SOCRobotNegotiator}'s is-selling flags.
     *<P>
     * If our player is losing a resource needed for the {@link #buildingPlan},
     * clear the plan if this is for the Special Building Phase (on the 6-player board).
     * In normal game play, we clear the building plan at the start of each turn.
     *<P>
     * Otherwise, only the game data is updated, nothing brain-specific.
     *
     * @param pl   Player to update; some elements take null. If null and {@code pn != -1}, will find {@code pl}
     *     using {@link SOCGame#getPlayer(int) game.getPlayer(pn)}.
     * @param pn   Player number from message (sometimes -1 for none or all)
     * @param action   {@link SOCPlayerElement#SET}, {@link SOCPlayerElement#GAIN GAIN},
     *     or {@link SOCPlayerElement#LOSE LOSE}
     * @param etype  Element type, such as {@link PEType#SETTLEMENTS} or {@link PEType#NUMKNIGHTS}
     * @param amount  The new value to set, or the delta to gain/lose
     * @since 2.0.00
     */
    @SuppressWarnings("fallthrough")
    protected void handlePLAYERELEMENT
        (SOCPlayer pl, final int pn, final int action, final PEType etype, final int amount)
    {
        if (etype == null)
            return;
        if ((pl == null) && (pn != -1))
            pl = game.getPlayer(pn);

        switch (etype)
        {
        case ROADS:
            SOCDisplaylessPlayerClient.handlePLAYERELEMENT_numPieces
                (pl, action, SOCPlayingPiece.ROAD, amount);
            break;

        case SETTLEMENTS:
            SOCDisplaylessPlayerClient.handlePLAYERELEMENT_numPieces
                (pl, action, SOCPlayingPiece.SETTLEMENT, amount);
            break;

        case CITIES:
            SOCDisplaylessPlayerClient.handlePLAYERELEMENT_numPieces
                (pl, action, SOCPlayingPiece.CITY, amount);
            break;

        case SHIPS:
            SOCDisplaylessPlayerClient.handlePLAYERELEMENT_numPieces
                (pl, action, SOCPlayingPiece.SHIP, amount);
            break;

        case NUMKNIGHTS:
            // PLAYERELEMENT(NUMKNIGHTS) is sent after a Soldier card is played.
            SOCDisplaylessPlayerClient.handlePLAYERELEMENT_numKnights
                (game, pl, action, amount);
            break;

        case CLAY:
            handlePLAYERELEMENT_numRsrc
                (pl, action, SOCResourceConstants.CLAY, "CLAY", amount);
            break;

        case ORE:
            handlePLAYERELEMENT_numRsrc
                (pl, action, SOCResourceConstants.ORE, "ORE", amount);
            break;

        case SHEEP:
            handlePLAYERELEMENT_numRsrc
                (pl, action, SOCResourceConstants.SHEEP, "SHEEP", amount);
            break;

        case WHEAT:
            handlePLAYERELEMENT_numRsrc
                (pl, action, SOCResourceConstants.WHEAT, "WHEAT", amount);
            break;

        case WOOD:
            handlePLAYERELEMENT_numRsrc
                (pl, action, SOCResourceConstants.WOOD, "WOOD", amount);
            break;

        case UNKNOWN_RESOURCE:
            /**
             * Note: if losing unknown resources, we first
             * convert player's known resources to unknown resources,
             * then remove mes's unknown resources from player.
             */
            handlePLAYERELEMENT_numRsrc
                (pl, action, SOCResourceConstants.UNKNOWN, "UNKNOWN", amount);
            break;

        case RESOURCE_COUNT:
            if (amount != pl.getResources().getTotal())
            {
                SOCResourceSet rsrcs = pl.getResources();

                if (D.ebugOn)
                {
                    client.sendText(game, ">>> RESOURCE COUNT ERROR FOR PLAYER " + pl.getPlayerNumber()
                        + ": " + amount + " != " + rsrcs.getTotal());
                }

                //
                //  fix it
                //
                if (pl.getPlayerNumber() != ourPlayerNumber)
                {
                    rsrcs.clear();
                    rsrcs.setAmount(amount, SOCResourceConstants.UNKNOWN);
                }
            }
            break;

        case SCENARIO_WARSHIP_COUNT:
            if (expectPLACING_ROBBER && (action == SOCPlayerElement.GAIN))
            {
                // warship card successfully played; clear the flag fields
                expectPLACING_ROBBER = false;
                waitingForGameState = false;
            }
            // fall through to default, so handlePLAYERELEMENT_simple will update game data

        default:
            // handle ASK_SPECIAL_BUILD, NUM_PICK_GOLD_HEX_RESOURCES, SCENARIO_CLOTH_COUNT, etc;
            // those are all self-contained informational fields that don't need any reaction from a bot.

            SOCDisplaylessPlayerClient.handlePLAYERELEMENT_simple
                (game, pl, pn, action, etype, amount, ourPlayerName);
            break;
        }

        ///
        /// if this during the ROLL_OR_CARD state, then update the is selling flags
        ///
        if (game.getGameState() == SOCGame.ROLL_OR_CARD)
        {
            negotiator.resetIsSelling();
        }
    }

    /**
     * Update a player's amount of a resource.
     *<ul>
     *<LI> If this is a {@link SOCPlayerElement#LOSE} action, and the player does not have enough of that type,
     *     the rest are taken from the player's UNKNOWN amount.
     *<LI> If we are losing from type UNKNOWN,
     *     first convert player's known resources to unknown resources
     *     (individual amount information will be lost),
     *     then remove mes's unknown resources from player.
     *<LI> If this is a SET action, and it's for our own robot player,
     *     check the amount against {@link #ourPlayerData}, and debug print
     *     if they don't match already.
     *</ul>
     *<P>
     * If our player is losing a resource needed for the {@link #buildingPlan},
     * clear the plan if this is for the Special Building Phase (on the 6-player board).
     * In normal game play, we clear the building plan at the start of each turn.
     *<P>
     * Before v2.0.00 this method directly took a {@link SOCPlayerElement} instead of that message's
     * {@code action} and {@code amount} fields.
     *
     * @param pl       Player to update
     * @param action   {@link SOCPlayerElement#SET}, {@link SOCPlayerElement#GAIN GAIN},
     *     or {@link SOCPlayerElement#LOSE LOSE}
     * @param rtype    Type of resource, like {@link SOCResourceConstants#CLAY}
     * @param rtypeStr Resource type name, for debugging
     * @param amount   The new value to set, or the delta to gain/lose
     * @since 1.1.00
     */
    @SuppressWarnings("unused")  // unnecessary dead-code warning "if (D.ebugOn)"
    protected void handlePLAYERELEMENT_numRsrc
        (SOCPlayer pl, final int action, int rtype, String rtypeStr, final int amount)
    {
        /**
         * for SET, check the amount of unknown resources against
         * what we think we know about our player.
         */
        if (D.ebugOn && (pl == ourPlayerData) && (action == SOCPlayerElement.SET)) 
        {
            if (amount != ourPlayerData.getResources().getAmount(rtype))
            {
                client.sendText(game, ">>> RSRC ERROR FOR " + rtypeStr
                    + ": " + amount + " != " + ourPlayerData.getResources().getAmount(rtype));
            }
        }

        /**
         * Update game data.
         */
        handleResources(action, pl, rtype, amount);

        /**
         * Clear building plan, if we just lost a resource we need.
         * Only necessary for Special Building Phase (6-player board),
         * because in normal game play, we clear the building plan
         * at the start of each turn.
         */
        BP plan = getBuildingPlan();
        if (waitingForSpecialBuild && (pl == ourPlayerData)
            && (action != SOCPlayerElement.GAIN)
            && ! plan.isEmpty())
        {
        	// TODO: This is only for the expansion - TBD if this is something we need to preserve
            final SOCPossiblePiece targetPiece = plan.getPlannedPiece(0);
            final SOCResourceSet targetResources = targetPiece.getResourcesToBuild();  // may be null

            if (! ourPlayerData.getResources().contains(targetResources))
            {
            	resetBuildingPlan();

                // The buildingPlan is clear, so we'll calculate
                // a new plan when our Special Building turn begins.
                // Don't clear decidedIfSpecialBuild flag, to prevent
                // needless plan calculation before our turn begins,
                // especially from multiple PLAYERELEMENT(LOSE),
                // as may happen for a discard.
            }
        }

    }

    public void setWaitingForTradeMsg(){
    	waitingForTradeMsg = true;
    }
    
    /**
     * Run a newly placed settlement through the playerTrackers.
     * Called only after {@link SOCGame#putPiece(SOCPlayingPiece)}
     * or {@link SOCGame#putTempPiece(SOCPlayingPiece)}.
     *<P>
     * During initial board setup, settlements aren't tracked when placed.
     * They are deferred until their corresponding road placement, in case
     * a human player decides to cancel their settlement and place it elsewhere.
     *
     * During normal play, the settlements are tracked immediately when placed.
     *
     * (Code previously in body of the run method.)
     * Placing the code in its own method allows tracking that settlement when the
     * road's putPiece message arrives.
     *
     * @param newSettlement The newly placed settlement for the playerTrackers
     * @param isCancel Is this our own robot's settlement placement, rejected by the server?
     *     If so, this method call will cancel its placement within the game data / robot data.
     * @since 1.1.00
     */
    protected void trackNewSettlement(SOCSettlement newSettlement, final boolean isCancel)
    {
        for (final SOCPlayerTracker tracker : playerTrackers)
        {
            if (tracker == null)
                continue;

            if (! isCancel)
                tracker.addNewSettlement(newSettlement, playerTrackers);
            else
                tracker.cancelWrongSettlement(newSettlement);
        }

        for (final SOCPlayerTracker tracker : playerTrackers)
        {
            if (tracker == null)
                continue;

            Iterator<SOCPossibleRoad> posRoadsIter = tracker.getPossibleRoads().values().iterator();

            while (posRoadsIter.hasNext())
            {
                posRoadsIter.next().clearThreats();
            }

            Iterator<SOCPossibleSettlement> posSetsIter = tracker.getPossibleSettlements().values().iterator();

            while (posSetsIter.hasNext())
            {
                posSetsIter.next().clearThreats();
            }
        }

        for (final SOCPlayerTracker tracker : playerTrackers)
        {
            if (tracker != null)
                tracker.updateThreats(playerTrackers);
        }

        if (isCancel)
        {
            return;  // <--- Early return, nothing else to do ---
        }

        ///
        /// see if this settlement bisected someone else's road
        ///
        int[] roadCount = { 0, 0, 0, 0, 0, 0 };  // Length should be SOCGame.MAXPLAYERS
        final SOCBoard board = game.getBoard();

        for (final int adjEdge : board.getAdjacentEdgesToNode(newSettlement.getCoordinates()))
        {
            final SOCRoutePiece rs = board.roadOrShipAtEdge(adjEdge);
            if (rs == null)
                continue;

            final int roadPN = rs.getPlayerNumber();

            roadCount[roadPN]++;

            if (roadCount[roadPN] == 2)
            {
                if (roadPN != ourPlayerNumber)
                {
                    ///
                    /// this settlement bisects another players road
                    ///
                    final SOCPlayerTracker tracker = playerTrackers[roadPN];
                    if (tracker != null)
                    {
                        //D.ebugPrintln("$$ updating LR Value for player "+tracker.getPlayer().getPlayerNumber());
                        //tracker.updateLRValues();
                    }
                }

                break;
            }
        }
        
        final int pNum = newSettlement.getPlayerNumber();

        ///
        /// update the speedups from possible settlements
        ///
        final SOCPlayerTracker tracker = playerTrackers[pNum];

        if (tracker != null)
        {
            Iterator<SOCPossibleSettlement> posSetsIter = tracker.getPossibleSettlements().values().iterator();

            while (posSetsIter.hasNext())
            {
                posSetsIter.next().updateSpeedup();
            }
        }

        ///
        /// update the speedups from possible cities
        ///
        if (tracker != null)
        {
            Iterator<SOCPossibleCity> posCitiesIter = tracker.getPossibleCities().values().iterator();

            while (posCitiesIter.hasNext())
            {
                posCitiesIter.next().updateSpeedup();
            }
        }
    }

    /**
     * Run a newly placed city through the PlayerTrackers.
     * @param newCity  The newly placed city
     * @param isCancel Is this our own robot's city placement, rejected by the server?
     *     If so, this method call will cancel its placement within the game data / robot data.
     * @since 1.1.00
     */
    protected void trackNewCity(final SOCCity newCity, final boolean isCancel)
    {
        final int newCityPN = newCity.getPlayerNumber();

        for (final SOCPlayerTracker tracker : playerTrackers)
        {
            if (tracker == null)
                continue;

            if (tracker.getPlayer().getPlayerNumber() == newCityPN)
            {
                if (! isCancel)
                    tracker.addOurNewCity(newCity);
                else
                    tracker.cancelWrongCity(newCity);

                break;
            }
        }

        if (isCancel)
        {
            return;  // <--- Early return, nothing else to do ---
        }

        ///
        /// update the speedups from possible settlements
        ///
        for (final SOCPlayerTracker tracker : playerTrackers)
        {
            if (tracker == null)
                continue;

            if (tracker.getPlayer().getPlayerNumber() == newCityPN)
            {
                Iterator<SOCPossibleSettlement> posSetsIter = tracker.getPossibleSettlements().values().iterator();

                while (posSetsIter.hasNext())
                {
                    posSetsIter.next().updateSpeedup();
                }

                break;
            }
        }

        ///
        /// update the speedups from possible cities
        ///
        for (final SOCPlayerTracker tracker : playerTrackers)
        {
            if (tracker == null)
                continue;

            if (tracker.getPlayer().getPlayerNumber() == newCityPN)
            {
                Iterator<SOCPossibleCity> posCitiesIter = tracker.getPossibleCities().values().iterator();

                while (posCitiesIter.hasNext())
                {
                    posCitiesIter.next().updateSpeedup();
                }

                break;
            }
        }
    }

    /**
     * Run a newly placed road or ship through the playerTrackers.
     *<P>
     * Before v2.0.00 this method was {@code trackNewRoad}.
     *
     * @param newPiece  The newly placed road or ship
     * @param isCancel Is this our own robot's road placement, rejected by the server?
     *     If so, this method call will cancel its placement within the game data / robot data.
     * @since 1.1.00
     */
    protected void trackNewRoadOrShip(final SOCRoutePiece newPiece, final boolean isCancel)
    {
        final int newRoadPN = newPiece.getPlayerNumber();

        for (final SOCPlayerTracker tracker : playerTrackers)
        {
            if (tracker == null)
                continue;

            tracker.takeMonitor();

            try
            {
                if (! isCancel)
                    tracker.addNewRoadOrShip(newPiece, playerTrackers);
                else
                    tracker.cancelWrongRoadOrShip(newPiece);
            }
            catch (Exception e)
            {
                tracker.releaseMonitor();
                if (alive)
                {
                    System.err.println("Exception caught - " + e);
                    e.printStackTrace();
                }
            } finally {
                tracker.releaseMonitor();
            }
        }

        for (final SOCPlayerTracker tracker : playerTrackers)
        {
            if (tracker == null)
                continue;

            tracker.takeMonitor();

            try
            {
                Iterator<SOCPossibleRoad> posRoadsIter = tracker.getPossibleRoads().values().iterator();

                while (posRoadsIter.hasNext())
                {
                    posRoadsIter.next().clearThreats();
                }

                Iterator<SOCPossibleSettlement> posSetsIter = tracker.getPossibleSettlements().values().iterator();

                while (posSetsIter.hasNext())
                {
                    posSetsIter.next().clearThreats();
                }
            }
            catch (Exception e)
            {
                tracker.releaseMonitor();
                if (alive)
                {
                    System.err.println("Exception caught - " + e);
                    e.printStackTrace();
                }
            } finally {
                tracker.releaseMonitor();
            }
        }

        ///
        /// update LR values and ETA
        ///
        for (final SOCPlayerTracker tracker : playerTrackers)
        {
            if (tracker == null)
                continue;

            tracker.updateThreats(playerTrackers);
            tracker.takeMonitor();

            try
            {
                if (tracker.getPlayer().getPlayerNumber() == newRoadPN)
                {
                    //D.ebugPrintln("$$ updating LR Value for player "+tracker.getPlayer().getPlayerNumber());
                    //tracker.updateLRValues();
                }

                //tracker.recalcLongestRoadETA();
            }
            catch (Exception e)
            {
                tracker.releaseMonitor();
                if (alive)
                {
                    System.err.println("Exception caught - " + e);
                    e.printStackTrace();
                }
            } finally {
                tracker.releaseMonitor();
            }
        }
    }

    /**
     *  We've asked for an illegal piece placement.
     *  Cancel and invalidate this planned piece, make a new plan.
     *  If {@link SOCGame#isSpecialBuilding()}, will set variables to
     *  force the end of our special building turn.
     *  Also handles illegal requests to buy development cards
     *  (piece type -2 in {@link SOCCancelBuildRequest}).
     *<P>
     *  Must update game data by calling {@link SOCGame#setGameState(int)} before calling this method.
     *<P>
     *  This method increments {@link #failedBuildingAttempts},
     *  but won't leave the game if we've failed too many times.
     *  The brain's run loop should make that decision.
     *<UL>
     * <LI> If {@link SOCGame#getGameState()} is {@link SOCGame#PLAY1},
     *   server likely denied us due to resources, not due to building plan
     *   being interrupted by another player's building before our special building phase.
     *   (Could also be due to a bug in the chosen building plan.)
     *   Will clear our building plan so we'll make a new one.
     * <LI> In other gamestates, assumes requested piece placement location was illegal.
     *   Will call {@link #cancelWrongPiecePlacementLocal(SOCPlayingPiece)}
     *   so we don't try again to build there.
     * <LI> Either way, sends a {@code CancelBuildRequest} message to the server.
     *</UL>
     *
     * @param mes Cancel message from server, including piece type
     * @since 1.1.00
     */
    protected void cancelWrongPiecePlacement(SOCCancelBuildRequest mes)
    {
        final boolean cancelBuyDevCard = (mes.getPieceType() == SOCPossiblePiece.CARD);  // == -2
        if (cancelBuyDevCard)
        {
            waitingForDevCard = false;
        } else {
            whatWeFailedToBuild = whatWeWantToBuild;
            ++failedBuildingAttempts;
        }
        waitingForGameState = false;

        final int gameState = game.getGameState();

        /**
         * if true, server likely denied us due to resources, not due to building plan
         * being interrupted by another player's building before our special building phase.
         * (Could also be due to a bug in the chosen building plan.)
         */
        final boolean gameStateIsPLAY1 = (gameState == SOCGame.PLAY1);

        if (! (gameStateIsPLAY1 || cancelBuyDevCard))
        {
            int coord = -1;
            switch (gameState)
            {
            case SOCGame.START1A:
            case SOCGame.START1B:
            case SOCGame.START2A:
            case SOCGame.START2B:
            case SOCGame.START3A:
            case SOCGame.START3B:
                coord = lastStartingPieceCoord;
                break;

            default:
                if (whatWeWantToBuild != null)
                    coord = whatWeWantToBuild.getCoordinates();
            }

            if (coord != -1)
            {
                SOCPlayingPiece cancelPiece;

                /**
                 * First, invalidate that piece in trackers, so we don't try again to
                 * build it. If we treat it like another player's new placement, we
                 * can remove any of our planned pieces depending on this one.
                 */
                switch (mes.getPieceType())
                {
                case SOCPlayingPiece.ROAD:
                    cancelPiece = new SOCRoad(dummyCancelPlayerData, coord, null);
                    break;

                case SOCPlayingPiece.SETTLEMENT:
                    cancelPiece = new SOCSettlement(dummyCancelPlayerData, coord, null);
                    break;

                case SOCPlayingPiece.CITY:
                    cancelPiece = new SOCCity(dummyCancelPlayerData, coord, null);
                    break;

                case SOCPlayingPiece.SHIP:
                    cancelPiece = new SOCShip(dummyCancelPlayerData, coord, null);
                    break;

                default:
                    cancelPiece = null;  // To satisfy javac
                }

                cancelWrongPiecePlacementLocal(cancelPiece);
            }
        } else {
            /**
             *  stop trying to build it now, but don't prevent
             *  us from trying later to build it.
             */
            whatWeWantToBuild = null;
            resetBuildingPlan();
        }

        /**
         * we've invalidated that piece in trackers.
         * - clear whatWeWantToBuild, buildingPlan
         * - set expectPLAY1, waitingForGameState
         * - reset counter = 0
         * - send CANCEL _to_ server, so all players get PLAYERELEMENT & GAMESTATE(PLAY1) messages.
         * - wait for the play1 message, then can re-plan another piece.
         * - update javadoc of this method (TODO)
         */

        if (gameStateIsPLAY1 || game.isSpecialBuilding())
        {
            // Shouldn't have asked to build this piece at this time.
            // End our confusion by ending our current turn. Can re-plan on next turn.
            failedBuildingAttempts = MAX_DENIED_BUILDING_PER_TURN;
            expectPLACING_ROAD = false;
            expectPLACING_SETTLEMENT = false;
            expectPLACING_CITY = false;
            expectPLACING_SHIP = false;
            decidedIfSpecialBuild = true;
            if (! cancelBuyDevCard)
            {
                // special building, currently in state PLACING_* ;
                // get our resources back, get state PLAY1 or SPECIALBUILD
                waitingForGameState = true;
                expectPLAY1 = true;
                client.cancelBuildRequest(game, mes.getPieceType());
            }
        }
        else if (gameState <= SOCGame.START3B)
        {
            switch (gameState)
            {
            case SOCGame.START1A:
                expectPUTPIECE_FROM_START1A = false;
                expectSTART1A = true;
                break;

            case SOCGame.START1B:
                expectPUTPIECE_FROM_START1B = false;
                expectSTART1B = true;
                break;

            case SOCGame.START2A:
                expectPUTPIECE_FROM_START2A = false;
                expectSTART2A = true;
                break;

            case SOCGame.START2B:
                expectPUTPIECE_FROM_START2B = false;
                expectSTART2B = true;
                break;

            case SOCGame.START3A:
                expectPUTPIECE_FROM_START3A = false;
                expectSTART3A = true;
                break;

            case SOCGame.START3B:
                expectPUTPIECE_FROM_START3B = false;
                expectSTART3B = true;
                break;
            }
            // The run loop will check if failedBuildingAttempts > (2 * MAX_DENIED_BUILDING_PER_TURN).
            // This bot will leave the game there if it can't recover.
        } else {
            expectPLAY1 = true;
            waitingForGameState = true;
            counter = 0;
            client.cancelBuildRequest(game, mes.getPieceType());
            // Now wait for the play1 message, then can re-plan another piece.
        }
    }

    /**
     * Remove our incorrect piece placement, it's been rejected by the server.
     * Take this piece out of trackers, without sending any response back to the server.
     *<P>
     * This method invalidates that piece in trackers, so we don't try again to
     * build there. Since we treat it like another player's new placement, we
     * can remove any of our planned pieces depending on this one.
     *<P>
     * Also calls {@link SOCPlayer#clearPotentialSettlement(int)},
     * clearPotentialRoad, or clearPotentialCity.
     *
     * @param cancelPiece Type and coordinates of the piece to cancel; null is allowed but not very useful.
     * @since 1.1.00
     */
    protected void cancelWrongPiecePlacementLocal(SOCPlayingPiece cancelPiece)
    {
        if (cancelPiece != null)
        {
            final int coord = cancelPiece.getCoordinates();

            switch (cancelPiece.getType())
            {
            case SOCPlayingPiece.SHIP:  // fall through to ROAD
            case SOCPlayingPiece.ROAD:
                trackNewRoadOrShip((SOCRoutePiece) cancelPiece, true);
                if (cancelPiece.getType() == SOCPlayingPiece.ROAD)
                    ourPlayerData.clearPotentialRoad(coord);
                else
                    ourPlayerData.clearPotentialShip(coord);
                if (game.getGameState() <= SOCGame.START3B)
                {
                    // needed for placeInitRoad() calculations
                    ourPlayerData.clearPotentialSettlement(lastStartingRoadTowardsNode);
                }
                break;

            case SOCPlayingPiece.SETTLEMENT:
                trackNewSettlement((SOCSettlement) cancelPiece, true);
                ourPlayerData.clearPotentialSettlement(coord);
                break;

            case SOCPlayingPiece.CITY:
                trackNewCity((SOCCity) cancelPiece, true);
                ourPlayerData.clearPotentialCity(coord);
                break;
            }
        }

        whatWeWantToBuild = null;
        resetBuildingPlan();
    }

    /**
     * Kill this brain's thread: clears its "alive" flag, stops pinger,
     * puts a null message into the event queue.
     */
    public void kill()
    {
        final SOCRobotPinger p = pinger;

        alive = false;
//        System.err.println(ourPlayerData.getName() + " - number of messages received while alive: " + numberOfMessagesReceived);

        try
        {
            if (p != null)
                p.stopPinger();
            gameEventQ.put(null);
        }
        catch (Exception exc) {}
    }

    /**
     * pause for a bit.
     *<P>
     * In a 6-player game, pause only 75% as long, to shorten the overall game delay,
     * except if {@link #waitingForTradeResponse}.
     * This is indicated by the {@link #pauseFaster} flag.
     *
     * @param msec  number of delays to apply.  by default, 500ms per delay
     */
    protected void pause(int delaySteps)
    {
    	if (delayTime > 0) {
    		int msec = delaySteps * delayTime;    	
			forcePause(msec);
		}
    }
    
    /**
     * Force an actual ms pause to wait for trade responses
     *<P>
     * When {@link SOCGame#isBotsOnly}, pause only 25% as long, to quicken the simulation
     * but not make it too fast to allow a person to observe.
     * Can change {@link #BOTS_ONLY_FAST_PAUSE_FACTOR} to adjust that percentage.
     *
     * @param msec
     */
    private void forcePause(int msec)
    {
        if (game.isBotsOnly)
        {
            msec = (int) (msec * BOTS_ONLY_FAST_PAUSE_FACTOR);
            if (msec == 0)
                return;  // will still yield within run() loop
        } else if (pauseFaster && ! waitingForTradeResponse) {
             msec = (msec / 2) + (msec / 4);
        }

         try
         {
             Thread.yield();
             if (msec > 2)  // skip very short sleeps from small BOTS_ONLY_FAST_PAUSE_FACTOR
                 sleep(msec);
         }
         catch (InterruptedException exc) {}
    }

    /**
     * place planned first settlement
     * @param firstSettlement  First settlement's node coordinate
     * @see #placeInitSettlement(int)
     */
    protected void placeFirstSettlement(final int firstSettlement)
    {
        //D.ebugPrintln("BUILD REQUEST FOR SETTLEMENT AT "+Integer.toHexString(firstSettlement));
        pause(1);
        lastStartingPieceCoord = firstSettlement;
        client.putPiece(game, new SOCSettlement(ourPlayerData, firstSettlement, null));
        pause(2);
    }

    /**
     * Place planned initial settlement after first one.
     * @param initSettlement  Second or third settlement's node coordinate,
     *   from {@link OpeningBuildStrategy#planSecondSettlement()} or
     *   from {@link OpeningBuildStrategy#planThirdSettlement()};
     *   should not be -1
     * @see #placeFirstSettlement(int)
     */
    protected void placeInitSettlement(final int initSettlement)
    {
        if (initSettlement)
        {
            // This could mean that the server (incorrectly) asked us to
            // place another second settlement, after we've cleared the
            // potentialSettlements contents.
            System.err.println("robot assert failed: initSettlement -1, "
                + ourPlayerData.getName() + " leaving game " + game.getName());
            failedBuildingAttempts = 2 + (2 * MAX_DENIED_BUILDING_PER_TURN);
            waitingForGameState = false;
            return;
        }

        //D.ebugPrintln("BUILD REQUEST FOR SETTLEMENT AT "+Integer.toHexString(secondSettlement));
        pause(1);
        lastStartingPieceCoord = initSettlement;
        client.putPiece(game, new SOCSettlement(ourPlayerData, initSettlement, null));
        pause(2);
    }

    /**
     * Plan and place a road attached to our most recently placed initial settlement,
     * in game states {@link SOCGame#START1B START1B}, {@link SOCGame#START2B START2B}.
     *<P>
     * Road choice is based on the best nearby potential settlements, and doesn't
     * directly check {@link SOCPlayer#isPotentialRoad(int) ourPlayerData.isPotentialRoad(edgeCoord)}.
     * If the server rejects our road choice, then {@link #cancelWrongPiecePlacementLocal(SOCPlayingPiece)}
     * will need to know which settlement node we were aiming for,
     * and call {@link SOCPlayer#clearPotentialSettlement(int) ourPlayerData.clearPotentialSettlement(nodeCoord)}.
     * The {@link #lastStartingRoadTowardsNode} field holds this coordinate.
     */
    public void planAndPlaceInitRoad()
    {
        // TODO handle ships here

        final int roadEdge = openingBuildStrategy.planInitRoad();

        //D.ebugPrintln("!!! PUTTING INIT ROAD !!!");
        pause(1);

        //D.ebugPrintln("Trying to build a road at "+Integer.toHexString(roadEdge));
        lastStartingPieceCoord = roadEdge;
        lastStartingRoadTowardsNode = openingBuildStrategy.getPlannedInitRoadDestinationNode();
        client.putPiece(game, new SOCRoad(ourPlayerData, roadEdge, null));
        pause(2);
    }
   
    /**
     * Select a new robber location and move the robber there.
     * Calls {@link RobberStrategy#getBestRobberHex()}.
     *<P>
     * Currently the robot always chooses to move the robber, never the pirate.
     */
    protected void moveRobber()
    {
        final int bestHex = robberStrategy.getBestRobberHex();

        D.ebugPrintlnINFO("!!! MOVING ROBBER !!!");
        client.moveRobber(game, ourPlayerData, bestHex);
        pause(4);
    }

    /**
     * make bank trades or port trades to get the required resources for executing a plan, if possible.
     * Calls {@link SOCRobotNegotiator#getOfferToBank(SOCBuildPlan, SOCResourceSet)}.
     *
     * @param buildPlan  Build plan to look for resources to build. {@code getOfferToBank(..)}
     *     will typically call {@link SOCBuildPlan#getFirstPieceResources()} to determine
     *     the resources we want. Can be {@code null} or an empty plan (returns false).
     * @return true if we sent a request to trade, false if
     *     we already have the resources or if we don't have
     *     enough to trade in for {@code buildPlan}'s required resources.
     */
    protected boolean tradeWithBank(BP buildPlan)
    {
        /* Assume the negotiator will detect this?
         * if (ourPlayerData.getResources().contains(targetResources))
        {
            return false;
        }*/

        SOCTradeOffer bankTrade = negotiator.getOfferToBank(buildPlan, ourPlayerData.getResources());

        if ((bankTrade != null) && (ourPlayerData.getResources().contains(bankTrade.getGiveSet())))
        {
            client.bankTrade(game, bankTrade.getGiveSet(), bankTrade.getGetSet());
            pause(4);

            return true;
        }

        return false;
    }

    /**
     * Make an offer to another player, or decide to make no offer.
     * Calls {@link SOCRobotNegotiator#makeOffer(SOCBuildPlan)}.
     * Will set {@link #waitingForTradeResponse} or {@link #doneTrading},
     * and update {@link #ourPlayerData}.{@link SOCPlayer#setCurrentOffer(SOCTradeOffer) setCurrentOffer()},
     *
     * @param buildPlan  our current build plan
     * @return true if we made an offer
     */
    protected boolean makeOffer(BP buildPlan)
    {
        boolean result = false;
        SOCTradeOffer offer = negotiator.makeOffer(buildPlan);
        // Consider this offer made right away, don't wait for rejections.
        negotiator.addToOffersMade(offer);
        ourPlayerData.setCurrentOffer(offer);
        negotiator.resetWantsAnotherOffer();

        if (offer != null)
        {
            D.ebugPrintlnINFO("MAKE OFFER - " + offer);
            tradeAccepted = false;
            ///
            ///  reset the offerRejections flag and check for human players
            ///
            boolean anyHumans = false;
            boolean[]to = offer.getTo();
            for (int i = 0; i < game.maxPlayers; i++)
            {
                if (to[i]) {
                    waitingForTradeResponsePlayer[i] = true;
                }
                if (! (game.isSeatVacant(pn) || game.getPlayer(pn).isRobot()))
                    anyHumans = true;
            }

            waitingForTradeResponse = true;
            tradeResponseTimeoutSec = (anyHumans)
                ? TRADE_RESPONSE_TIMEOUT_SEC_HUMANS
                : TRADE_RESPONSE_TIMEOUT_SEC_BOTS_ONLY;
            counter = 0;
            client.offerTrade(game, offer);
            result = true;
        }
        else
        {
            doneTrading = true;
            waitingForTradeResponse = false;
        }
        
        return result;
    }

    /**
     * Plan a counter offer to another player, and make it from our client.
     * Calls {@link SOCRobotNegotiator#makeCounterOffer(SOCTradeOffer)},
     * then {@link #ourPlayerData}{@link SOCPlayer#setCurrentOffer(SOCTradeOffer) .setCurrentOffer(..)}
     * with the result or {@code null}. Updates {@link #waitingForTradeResponse} flag.
     * If no counteroffer is made here, sets {@link #doneTrading}.
     *
     * @param offer  the other player's offer
     * @return true if we made and sent an offer
     */
    protected boolean makeCounterOffer(SOCTradeOffer offer)
    {
        boolean result = false;

        SOCTradeOffer counterOffer = negotiator.makeCounterOffer(offer);
        
        if (counterOffer != null)
        {
            final int pn = offer.getFrom();

            // ensure counter-offers are only sent to the person we're countering, otherwise synchronization issues arise
            boolean[] to = counterOffer.getTo();
            for (int i=0; i<to.length; i++) {
                to[i] = false;
            }
            to[pn] = true;            
            
            ourPlayerData.setCurrentOffer(counterOffer);
            negotiator.addToOffersMade(counterOffer);
            ///
            ///  reset the offerRejections flag
            ///
            tradeAccepted = false;
            waitingForTradeResponsePlayer[pn] = true;
            waitingForTradeResponse = true;
            tradeResponseTimeoutSec = (game.getPlayer(pn).isRobot())
                ? TRADE_RESPONSE_TIMEOUT_SEC_BOTS_ONLY
                : TRADE_RESPONSE_TIMEOUT_SEC_HUMANS;
            counter = 0;
            client.offerTrade(game, counterOffer);
            result = true;
        }
        else
        {
            // ??? This seems to cause problems...
            //  We get here if we got an offer which we don't want to accept, but may want to counter (based on considerOffer), but were unable to 
            //  come up with a counter-offer (based on makeCounterOffer).  
            //  This should be handled exactly as though we rejected the offer from the beginning.
            //doneTrading = true;
            //waitingForTradeResponse = false;
        }

        return result;
    }

    /**
     * Make a completed offer to another player.
     * 
     * Performs a check that the offer is not null, which can occur if 
     * {@link StacRobotDeclarativeMemory#retrieveBestCompletedTradeOffer()} has "forgotten" the best completed offer.
     * 
     * bestCompletedOffer may not be null when this method is called!
     *
     * @param offer the partial offer we received
     * @return true if we made an offer
     * @author Markus Guhe
     */
    protected boolean makeCompletedOffer(SOCTradeOffer offer)
    {
        boolean result = false;
        SOCTradeOffer bestCompletedOffer = negotiator.getBestCompletedOffer(); 
        //copied from makeCounterOffer
        if (offer != null && bestCompletedOffer != null)
        {
            tradeAccepted = false;
            waitingForTradeResponsePlayer[offer.getFrom()] = true;
            waitingForTradeResponse = true;
            counter = 0;
        
            //specific completedOffer stuff
            int from = ourPlayerData.getPlayerNumber();
            boolean[] toPlayer;
            toPlayer = new boolean [4];
            toPlayer[0] = false; toPlayer[1] = false; toPlayer[2] = false; toPlayer[3] = false;
            toPlayer[bestCompletedOffer.getFrom()] = true;
            SOCTradeOffer actualCounterOffer = new SOCTradeOffer(bestCompletedOffer.getGame(), from, toPlayer, bestCompletedOffer.getGetSet(), bestCompletedOffer.getGiveSet());
        
            //again copied from makeCounterOffer
            client.offerTrade(game, actualCounterOffer);
            ourPlayerData.setCurrentOffer(actualCounterOffer);
            negotiator.addToOffersMade(actualCounterOffer);

            result = true;        
        }
        else
        {
            doneTrading = true;
            waitingForTradeResponse = false;
        }

        return result;
    }


    /**
     * this is for debugging
     */
    protected void debugInfo()
    {
        /*
           if (D.ebugOn) {
           //D.ebugPrintln("$===============");
           //D.ebugPrintln("gamestate = "+game.getGameState());
           //D.ebugPrintln("counter = "+counter);
           //D.ebugPrintln("resources = "+ourPlayerData.getResources().getTotal());
           if (expectSTART1A)
           //D.ebugPrintln("expectSTART1A");
           if (expectSTART1B)
           //D.ebugPrintln("expectSTART1B");
           if (expectSTART2A)
           //D.ebugPrintln("expectSTART2A");
           if (expectSTART2B)
           //D.ebugPrintln("expectSTART2B");
           if (expecROLL_OR_CARD)
           //D.ebugPrintln("expectROLL_OR_CARD");
           if (expectPLAY1)
           //D.ebugPrintln("expectPLAY1");
           if (expectPLACING_ROAD)
           //D.ebugPrintln("expectPLACING_ROAD");
           if (expectPLACING_SETTLEMENT)
           //D.ebugPrintln("expectPLACING_SETTLEMENT");
           if (expectPLACING_CITY)
           //D.ebugPrintln("expectPLACING_CITY");
           if (expectPLACING_ROBBER)
           //D.ebugPrintln("expectPLACING_ROBBER");
           if (expectPLACING_FREE_ROAD1)
           //D.ebugPrintln("expectPLACING_FREE_ROAD1");
           if (expectPLACING_FREE_ROAD2)
           //D.ebugPrintln("expectPLACING_FREE_ROAD2");
           if (expectPUTPIECE_FROM_START1A)
           //D.ebugPrintln("expectPUTPIECE_FROM_START1A");
           if (expectPUTPIECE_FROM_START1B)
           //D.ebugPrintln("expectPUTPIECE_FROM_START1B");
           if (expectPUTPIECE_FROM_START2A)
           //D.ebugPrintln("expectPUTPIECE_FROM_START2A");
           if (expectPUTPIECE_FROM_START2B)
           //D.ebugPrintln("expectPUTPIECE_FROM_START2B");
           if (expectDICERESULT)
           //D.ebugPrintln("expectDICERESULT");
           if (expectDISCARD)
           //D.ebugPrintln("expectDISCARD");
           if (expectMOVEROBBER)
           //D.ebugPrintln("expectMOVEROBBER");
           if (expectWAITING_FOR_DISCOVERY)
           //D.ebugPrintln("expectWAITING_FOR_DISCOVERY");
           if (waitingForGameState)
           //D.ebugPrintln("waitingForGameState");
           if (waitingForOurTurn)
           //D.ebugPrintln("waitingForOurTurn");
           if (waitingForTradeMsg)
           //D.ebugPrintln("waitingForTradeMsg");
           if (waitingForDevCard)
           //D.ebugPrintln("waitingForDevCard");
           if (moveRobberOnSeven)
           //D.ebugPrintln("moveRobberOnSeven");
           if (waitingForTradeResponse)
           //D.ebugPrintln("waitingForTradeResponse");
           if (doneTrading)
           //D.ebugPrintln("doneTrading");
           if (ourTurn)
           //D.ebugPrintln("ourTurn");
           //D.ebugPrintln("whatWeWantToBuild = "+whatWeWantToBuild);
           //D.ebugPrintln("#===============");
           }
         */
    }

    /**
     * For each player in game:
     * client.sendText, and debug-print to console, game.getPlayer(i).getResources()
     */
    protected void printResources()
    {
        if (D.ebugOn)
        {
            for (int i = 0; i < game.maxPlayers; i++)
            {
                SOCResourceSet rsrcs = game.getPlayer(i).getResources();
                String resourceMessage = "PLAYER " + i + " RESOURCES: ";
                resourceMessage += (rsrcs.getAmount(SOCResourceConstants.CLAY) + " ");
                resourceMessage += (rsrcs.getAmount(SOCResourceConstants.ORE) + " ");
                resourceMessage += (rsrcs.getAmount(SOCResourceConstants.SHEEP) + " ");
                resourceMessage += (rsrcs.getAmount(SOCResourceConstants.WHEAT) + " ");
                resourceMessage += (rsrcs.getAmount(SOCResourceConstants.WOOD) + " ");
                resourceMessage += (rsrcs.getAmount(SOCResourceConstants.UNKNOWN) + " ");
                client.sendText(game, resourceMessage);
                D.ebugPrintlnINFO(resourceMessage);
            }
        }
    }

    /**
	*Wake up the brain by setting suspended flag to false
	*/
    public void awaken(){
    	suspended = false;
    }
    
    /**
     * During the loading procedure, the brain state during saving needs to be recreated.
     * @param info the brain state to update to
     */
	public void partialUpdateFromInfo(StacRobotBrainInfo info) {
    	buildingPlan = (BP) info.buildingPlan;
    	counter = info.counter;
    	doneTrading = info.doneTrading;
    	lastMove = info.lastMove;
    	lastTarget = info.lastTarget;
    	decisionMaker.monopolyChoice = info.monopolyChoice;
    	numberOfMessagesReceived = info.numberOfMessagesReceived;
    	oldGameState = info.oldGameState;
    	ourTurn = info.ourTurn;
    	decisionMaker.resourceChoices = info.resourceChoices;
    	tradeAccepted = info.tradeAccepted;
    	whatWeFailedToBuild = info.whatWeFailedToBuild;
    	whatWeWantToBuild = info.whatWeWantToBuild;
    	
    	expectSTART1A = info.expectSTART1A;
        expectSTART1B = info.expectSTART1B;
        expectSTART2A = info.expectSTART2A;
        expectSTART2B = info.expectSTART2B;
        expectPLAY = info.expectPLAY;
        expectPLAY1 = info.expectPLAY1;
        expectPLACING_ROAD = info.expectPLACING_ROAD;
        expectPLACING_SETTLEMENT = info.expectPLACING_SETTLEMENT;        
        expectPLACING_CITY = info.expectPLACING_CITY;
        expectPLACING_ROBBER = info.expectPLACING_ROBBER;
        expectPLACING_FREE_ROAD1 = info.expectPLACING_FREE_ROAD1;
        expectPLACING_FREE_ROAD2 = info.expectPLACING_FREE_ROAD2;
        expectPUTPIECE_FROM_START1A = info.expectPUTPIECE_FROM_START1A;
        expectPUTPIECE_FROM_START1B = info.expectPUTPIECE_FROM_START1B;
        expectPUTPIECE_FROM_START2A = info.expectPUTPIECE_FROM_START2A;
        expectPUTPIECE_FROM_START2B = info.expectPUTPIECE_FROM_START2B;
        expectDICERESULT = info.expectDICERESULT;
        expectDISCARD = info.expectDISCARD;
        expectMOVEROBBER = info.expectMOVEROBBER;
        expectWAITING_FOR_DISCOVERY = info.expectWAITING_FOR_DISCOVERY;
        expectWAITING_FOR_MONOPOLY = info.expectWAITING_FOR_MONOPOLY;
    	
        waitingForGameState = info.waitingForGameState;
        waitingForOurTurn = info.waitingForOurTurn;
        waitingForTradeMsg = info.waitingForTradeMsg;
        waitingForDevCard = info.waitingForDevCard;
        moveRobberOnSeven = info.moveRobberOnSeven;
        waitingForTradeResponse = info.waitingForTradeResponse;
	}
	
    /**
     * @return a container with all the flags and parameters describing the brain's current state.
     */
	public StacRobotBrainInfo getInfo() {
		return new StacRobotBrainInfo(this);
	}
	
	 /**
     * @return the game data
     */
    public SOCGame getGame()
    {
        return game;
    }

    /**
     * @return our player data
     * @see #getOurPlayerNumber()
     */
    public SOCPlayer getOurPlayerData()
    {
        return ourPlayerData;
    }

    /**
     * Get our player number, as set in {@link #setOurPlayerData()}.
     * @return Our {@link #getOurPlayerData()}'s player number
     * @since 2.0.00
     */
    public final int getOurPlayerNumber()
    {
        return ourPlayerNumber;
    }

    /**
     * Get the current building plan.
     *
     * @return the building plan
     * @see #resetBuildingPlan()
     */
    public BP getBuildingPlan()
    {
        return buildingPlan;
    }
    
    /**
     * clears the stack describing the current building plan.
     * NOTE should be called every time we planStuff as checking if the BuildingPlan is empty is not good enough if our memory is weak or decaying
     * @see #resetFieldsAtEndTurn()
     */
    public void resetBuildingPlan()
    {
    	buildingPlan.clear();
    }
    
    /**
     * @return the player trackers (one per player number, including this robot; vacant seats are null)
     */
    public SOCPlayerTracker[] getPlayerTrackers()
    {
        return playerTrackers;
    }

    /**
     * @return our player tracker
     */
    public SOCPlayerTracker getOurPlayerTracker()
    {
        return ourPlayerTracker;
    }	
	
    // Functions which must be defined by an instantiating subclass
    
    /**
     * consider an offer made by another player
     *
     * @param offer  the offer to consider
     * @return a code that represents how we want to respond
     * note: a negative result means we do nothing
     */
    protected abstract int considerOffer(SOCTradeOffer offer);
	
    /**
     * Handle the tracking of changing resources.  Allows us to determine how accurately this is tracked 
     *   eg full tracking of unknowns vs. cognitive modelling
     * @param action  {@link SOCPlayerElement#SET}, {@link SOCPlayerElement#GAIN GAIN},
     *     or {@link SOCPlayerElement#LOSE LOSE}
     * @param player  Player to update
     * @param resourceType  Type of resource, like {@link SOCResourceConstants#CLAY}
     * @param amount  The new value to set, or the delta to gain/lose
     */
    protected abstract void handleResources(int action, SOCPlayer player, int resourceType, int amount);
   	
    /**
     * Creates a decision maker
     * @return the decision maker depending on the type of brain
     */
    protected abstract DM createDM();
    
    /**
     * Recreates a decision maker
     * @return the decision maker depending on the type of brain
     */
    public abstract void recreateDM();
    
    /**
     * Creates a Negotiator object
     * @return the Negotiator depending on the brain type
     */
    protected abstract N createNegotiator();

    /**
     * Creates and returns a {@link SOCBuildingSpeedEstimate} factory.
     *
     * @return a factory for this brain
     * @see #setStrategyFields()
     * @see #getEstimatorFactory()
     * @since 2.4.50
     */
    protected abstract SOCBuildingSpeedEstimateFactory createEstimatorFactory();
	
    /**
     * Contains the logic for handling messages received via the chat interface, such as trades or inform of resources/build plans
     * @param gtm
     */
    protected abstract void handleChat(SOCGameTextMsg gtm);

    /**
     * Perform any specific actions needed by this brain at start of the main part of any player's turn:
     * Dice roll actions are done, game state just became {@link SOCGame#PLAY1}.
     *
     * @see #endTurnActions()
     */
    protected abstract void startTurnMainActions();
    
    /**
     * Some robot types require specific actions just before ending their turn, which may result in continuing their turn for a little while.
     * e.g. TRY_N_BEST agent may decide to try another build plan before ending its turn
     *<P>
     * If returns true, caller will call {@link #resetFieldsAtEndTurn()}.
     *<P>
     * Is called only after {@link #considerScenarioTurnFinalActions()} returns false.
     *
     * @return true if can end turn, false otherwise
     * @see #startTurnMainActions()
     */
    protected abstract boolean endTurnActions();

    /**
     * Is waiting for a reply to trades via the chat interface
     * @return
     */
    protected abstract boolean isWaiting();

    /**
     * @return true if this robot is the old SOC robot or a newer Stac version
     */
    protected abstract boolean isLegacy();

    /**
     * @param numbers the current resources in hand of the player we are estimating for
     * @return an estimate of time to build something
     */
    public abstract SOCBuildingSpeedEstimate getEstimator(SOCPlayerNumbers numbers);

    /**
     * @return an estimate of time to build something
     */
    public abstract SOCBuildingSpeedEstimate getEstimator();

    /**
     * Get this brain's {@link SOCBuildingSpeedEstimate} factory.
     * Is typically set from {@link #createEstimatorFactory()} during construction.
     *
     * @return This brain's factory
     * @see #getEstimator(SOCPlayerNumbers)
     * @since 2.4.50
     */
    public SOCBuildingSpeedEstimateFactory getEstimatorFactory()
    {
        return bseFactory;
    }

    /**
     * Inform the brain of the final game result.  Brain implementations may have some bookkeeping to do.
     * @param message
     */
    protected abstract void handleGAMESTATS(SOCGameStats message);

    /**
     * Creates the stack containing the steps in the buildplan
     * @return the buildingPlan stack 
     */
    protected abstract BP createBuildPlan();
    
    /**
     * Announces participation in game chat, if robot is of specific type.
     */
    public abstract void startGameChat();
    
    /**
     * Clears all flags waiting for a trade message.
     */
    public abstract void clearTradingFlags(String text);

    /**
     * Clears all flags waiting for a bank or player trade message.
     * {@link #waitingForTradeResponse}, {@link #waitingForTradeMsg},
     * any flags added in a third-party robot brain.
     *
     * @param isBankTrade  True if was bank/port trade, not player trade
     * @param wasAllowed  True if trade was successfully offered or completed,
     *     false if server sent a message disallowing it
     * @see #tradeStopWaitingClearOffer()
     * @see #handleTradeResponse(int, boolean)
     * @since 2.4.50
     */
    public void clearTradingFlags(final boolean isBankTrade, final boolean wasAllowed)
    {
        // This method clears both fields regardless of isBankTrade,
        // but third-party bots might override it and use that parameter

        waitingForTradeMsg = false;
        waitingForTradeResponse = false;
    }
    
    ///debug save method/////
    /**
     * initiates a save; to be called during the run loop for choosing when to save
     */
    public abstract void saveGame();
    
    /**
     * Handles messages received from the server, which announce specific actions (e.g. the execution of a robbery)
     * @param gtm
     */
    public abstract void handleGameTxtMsg(SOCGameTextMsg gtm); 

    /**
     * Handles results returned from the parser.
     * @param mes  The message containing the parse result received from the server
     */
    public abstract void handleParseResult(SOCParseResult mes);
    
    /////////////////////////
    
    //////////methods for extracting features from a current game state/////////////////////
    
    /**
     * This method needs access to the game object for computing some features which can not just be observed 
     * (e.g. does the player have a city/settlement isolated, what is the size of the players longest possible road not built yet etc)
     * @return the extracted features without the trades and bps counters as we have no way of keeping track of past ones nor measuring the future ones
     */
    public ExtGameStateRow turnStateIntoEGSR(){
    	ExtGameStateRow egsr = new ExtGameStateRow(0, getGame().getName());
    	//empty counters
		int[][] tradesCounter = new int[4][4];
		int[][] bppCounter = new int[4][6];
		//initialise the counters to 0
		Arrays.fill(tradesCounter[0],0);Arrays.fill(tradesCounter[1],0);Arrays.fill(tradesCounter[2],0);Arrays.fill(tradesCounter[3],0);
		Arrays.fill(bppCounter[0],0);Arrays.fill(bppCounter[1],0);Arrays.fill(bppCounter[2],0);Arrays.fill(bppCounter[3],0);
        egsr.setPastPBPs(StacDBHelper.transformToIntegerArr2(bppCounter));
        egsr.setPastTrades(StacDBHelper.transformToIntegerArr2(tradesCounter));
        egsr.setFuturePBPs(StacDBHelper.transformToIntegerArr2(bppCounter));
        egsr.setFutureTrades(StacDBHelper.transformToIntegerArr2(tradesCounter));
		
        /**
         * NOTE: the fact that both connected and isolated get set if a player joins without playing is annoying, but hopefully that won't be too much noise
         * as we are checking for null players again when we are selecting the features.
         */
        int[] territoryConnected = calculateAllConnectedTerr();
        int[] territoryIsolated = calculateAllIsolatedTerr();
        int[] distanceToPort = new int[4];
        int[] distanceToNextLegalLoc = new int[4];
        int[] distanceToOpp = new int[4];
        calculateAllDistances(distanceToPort, distanceToOpp, distanceToNextLegalLoc);
        int[] longestRoads = calculateAllLongestRoads();
        int[] longestPossibleRoads = calculateAllLongestPossibleRoads();
        
        //intialise arrays required for the egsr;
        int[] etws = new int[4];
        int[][] avgEtbs = new int [4][2];
        int[][] setEtbs = new int [4][2];
        int[][] cityEtbs = new int [4][2];
        int[][] roadEtbs = new int [4][2];
        int[][] devEtbs = new int [4][2];
        int[][] rssTypeNNumber = new int[4][5];
        
        //calculate all etbs and etws here
        etws = calculateAllCurrentETWs();
        calculateAllCurrentETBs(avgEtbs,setEtbs,cityEtbs,roadEtbs,devEtbs);
        
        //calculate all rssTypeNNumber here
        calculateAllRssTypeNNumber(rssTypeNNumber);
        
        //set everything for the egsr here
        egsr.setETWs(etws);
        egsr.setAvgETBs(StacDBHelper.transformToIntegerArr2(avgEtbs));
        egsr.setRoadETBs(StacDBHelper.transformToIntegerArr2(roadEtbs));
        egsr.setSettETBs(StacDBHelper.transformToIntegerArr2(setEtbs));
        egsr.setCityETBs(StacDBHelper.transformToIntegerArr2(cityEtbs));
        egsr.setDevETBs(StacDBHelper.transformToIntegerArr2(devEtbs));
        egsr.setRssTypeAndNumber(StacDBHelper.transformToIntegerArr2(rssTypeNNumber));
        egsr.setTerritoryConnected(StacDBHelper.transformToIntegerArr(territoryConnected));
        egsr.setTerritoryIsolated(StacDBHelper.transformToIntegerArr(territoryIsolated));
        egsr.setDistanceToPort(StacDBHelper.transformToIntegerArr(distanceToPort));
        egsr.setDistanceToOpponents(StacDBHelper.transformToIntegerArr(distanceToOpp));
        egsr.setDistanceToNextLegalLoc(StacDBHelper.transformToIntegerArr(distanceToNextLegalLoc));
        egsr.setLongestRoads(StacDBHelper.transformToIntegerArr(longestRoads));
        egsr.setLongestPossibleRoads(StacDBHelper.transformToIntegerArr(longestPossibleRoads));
    	return egsr;
    }
    
	/**
	 * updates the array containing the rss type and number of settlements/cities touching them for each player;
	 * NOTE: this is ignoring the robber's current location on board
	 * @param rssTypeNNumber
	 */
    protected void calculateAllRssTypeNNumber(int[][] rssTypeNNumber){
    	for (SOCPlayer p : getGame().getPlayers()) {
            int pn = p.getPlayerNumber();
            //get the player numbers
            SOCPlayerNumbers playerNumbers = p.getNumbers();
            
            rssTypeNNumber[pn][0] = playerNumbers.getNumbersForResource(SOCResourceConstants.CLAY).size();
            rssTypeNNumber[pn][1] = playerNumbers.getNumbersForResource(SOCResourceConstants.ORE).size();
            rssTypeNNumber[pn][2] = playerNumbers.getNumbersForResource(SOCResourceConstants.SHEEP).size();
            rssTypeNNumber[pn][3] = playerNumbers.getNumbersForResource(SOCResourceConstants.WHEAT).size();
            rssTypeNNumber[pn][4] = playerNumbers.getNumbersForResource(SOCResourceConstants.WOOD).size();
    	}
    }
    
    /**
	 * Updates all arrays containing the current etbs for each player both considering the robber location and without
	 * @param avgEtbs
	 * @param setEtbs
	 * @param cityEtbs
	 * @param roadEtbs
	 * @param devEtbs
	 */
    private void calculateAllCurrentETBs(int[][]avgEtbs,int[][]setEtbs,int[][]cityEtbs,int[][]roadEtbs,int[][]devEtbs){
    	int robber = getGame().getBoard().getRobberHex();
    	for (SOCPlayer p : getGame().getPlayers()) {
            int pn = p.getPlayerNumber();
            //get the player numbers
            SOCPlayerNumbers playerNumbers = p.getNumbers();

            //get the port flags
            boolean[] portFlags = p.getPortFlags();
            
			//in here calculate the etb's/etw's for the player and include in the egsr (all etb's are from nothing; maybe I should also measure from now?)
	        SOCBuildingSpeedEstimate estimator = new SOCBuildingSpeedFast();//always use the fast estimator;
	        estimator.recalculateEstimates(playerNumbers);
	        int[] speeds = estimator.getEstimatesFromNothingFast(portFlags);
		    int avg = 0;
	        for (int j = SOCBuildingSpeedEstimate.MIN;j < SOCBuildingSpeedEstimate.MAXPLUSONE; j++){
		    	avg += speeds[j];
		    }
	        //etbs ignoring the robber's effect
	        avgEtbs[pn][0] = avg/SOCBuildingSpeedEstimate.MAXPLUSONE;//avg etb's 
	        roadEtbs[pn][0] = speeds[SOCBuildingSpeedEstimate.ROAD];//road etb's
		    setEtbs[pn][0] = speeds[SOCBuildingSpeedEstimate.SETTLEMENT];//sett etb's
		    cityEtbs[pn][0] = speeds[SOCBuildingSpeedEstimate.CITY];//city etb's
		    devEtbs[pn][0] = speeds[SOCBuildingSpeedEstimate.CARD];//dev etb's
		    
	        estimator.recalculateEstimates(playerNumbers,robber);
	        int[] speedsRob = estimator.getEstimatesFromNothingFast(portFlags);
		    int avgRob = 0;
	        for (int j = SOCBuildingSpeedEstimate.MIN;j < SOCBuildingSpeedEstimate.MAXPLUSONE; j++){
		    	avgRob += speedsRob[j];
		    }
	        
	        //etbs including the robber's effect
	        avgEtbs[pn][1] = avgRob/SOCBuildingSpeedEstimate.MAXPLUSONE;//avg etb's 
	        roadEtbs[pn][1] = speedsRob[SOCBuildingSpeedEstimate.ROAD];//road etb's
		    setEtbs[pn][1] = speedsRob[SOCBuildingSpeedEstimate.SETTLEMENT];//sett etb's
		    cityEtbs[pn][1] = speedsRob[SOCBuildingSpeedEstimate.CITY];//city etb's
		    devEtbs[pn][1] = speedsRob[SOCBuildingSpeedEstimate.CARD];//dev etb's
    	}
	}
    
    /**
     * Computes the estimated time to win for each player.
	 * @return an array with the etws following players' the order on the board (based on their player numbers)
	 */
	private int[] calculateAllCurrentETWs(){
		SOCGame game = getGame();
		
		int[] winGameETAs = new int[game.maxPlayers];
	    for (int i = game.maxPlayers - 1; i >= 0; --i)
	    	winGameETAs[i] = 500;
	    Map<Integer, SOCPlayerTracker> playerTrackers = new HashMap<>();
	    playerTrackers = getPlayerTrackers();
	    
	    Iterator trackersIter = playerTrackers.values().iterator();
	    while (trackersIter.hasNext()){
	        SOCPlayerTracker tracker = (SOCPlayerTracker) trackersIter.next();
	        try{
	        	tracker.recalcWinGameETA();
	        	winGameETAs[tracker.getPlayer().getPlayerNumber()] = tracker.getWinGameETA();
	        }catch (NullPointerException e){
	             winGameETAs[tracker.getPlayer().getPlayerNumber()] = 500;
	        }
	    }
	    return winGameETAs;
	}
	
	/**
	 * Computes the current longest road for each player (this can be argued as observed feature though..) 
	 * @return the longest roads for each player based on their position at the board (player numbers)
	 */
	protected int[] calculateAllLongestRoads(){
		SOCGame game = getGame();
		int[] posLR = new int[game.maxPlayers];
		Arrays.fill(posLR, 0);
		for (SOCPlayer p : game.getPlayers()) {
            String pName = p.getName();
            int pn = p.getPlayerNumber();
            if(pName!=null){
            	posLR[pn] = p.calcLongestRoad2();
            }
		}
		return posLR;
	}
	
	/**
	 * Calculates distances to nearest port, opponent settlement or legal place to build in number of road pieces/edges up to a maximum of 3
	 * @param toPort
	 * @param toOpp
	 * @param toLegal
	 */
	protected void calculateAllDistances(int[] toPort, int[] toOpp, int[] toLegal){
		SOCGame game = getGame();
		Arrays.fill(toPort, 0);
		Arrays.fill(toOpp, 0);
		Arrays.fill(toLegal, 0);
		SOCBoard bd = game.getBoard();
		
		//very slow and complex calculation, hence why it should rather be done while replaying than online
		for (SOCPlayer p : game.getPlayers()) {
            String pName = p.getName();
            int pn = p.getPlayerNumber();
            if(pName!=null){
            	List<Integer> nodes2Away;
            	Iterator it;
	            // for each city
	            it = p.getCities().iterator();
	            while (it.hasNext()) {
	                SOCCity c = (SOCCity) it.next();
	                //check what is there at every 2 roads away
	                nodes2Away = bd.getAdjacentNodesToNode2Away(c.getCoordinates());
	                for(Integer node : nodes2Away){
	                	if(p.isLegalSettlement(node)){
	                		toLegal[pn] = 2;
	                		if(bd.getPortTypeFromNodeCoord(node)!= -1)
	                			toPort[pn] = 2;
	                	}else if(bd.settlementAtNode(node) != null){ 
	                		//check that is not one of our settlements
	                		if(!p.hasSettlementOrCityAtNode(node))
	                			toOpp[pn] = 2;
	                	}
	                }
	            }
	            // and for each settlement
	            it = p.getSettlements().iterator();
	            while (it.hasNext()) {
	                SOCSettlement s = (SOCSettlement) it.next();
	                //check what is there at every 2 roads away
	                nodes2Away = bd.getAdjacentNodesToNode2Away(s.getCoordinates());
	                for(Integer node : nodes2Away){
	                	if(p.isLegalSettlement(node)){
	                		toLegal[pn] = 2;
	                		if(bd.getPortTypeFromNodeCoord(node)!= -1)
	                			toPort[pn] = 2;
	                	}else if(bd.settlementAtNode(node) != null){ 
	                		//check that is not one of our settlements
	                		if(!p.hasSettlementOrCityAtNode(node))
	                			toOpp[pn] = 2;
	                	}
	                }
	            }
	        //if this is not a null player and we haven't set any of these to 2 than need to set them to 3 (roads or longer)
            if(toOpp[pn] == 0)
            	toOpp[pn] = 3;
            if(toPort[pn] == 0)
            	toPort[pn] = 3;
            if(toLegal[pn] == 0)
            	toLegal[pn] = 3;
            }
		}
	}
	
	/**
	 * Computes if the players have their settlements connected via their own road pieces.
	 * @return an array containing binary values, 1 if all settlements are connected, 0 otherwise
	 */
	private int[] calculateAllConnectedTerr(){
		SOCGame game = getGame();
		int[] connT = new int[game.maxPlayers];
		Arrays.fill(connT, 1);
		for (SOCPlayer p : game.getPlayers()) {
            String pName = p.getName();
            int pn = p.getPlayerNumber();
            if(pName!=null){
            	//pick one settlement or city from the list of pieces
            	SOCPlayingPiece start = null;
            	for(Object o : p.getPieces()){
            		int t = ((SOCPlayingPiece) o).getType();
            		if(t==SOCPlayingPiece.SETTLEMENT || t==SOCPlayingPiece.CITY){
            			start = (SOCPlayingPiece) o;
            			break;
            		}
            	}
            	//if the list was not empty
            	if(start != null){
	            	//for each sett in list of sett check if connected, else set to 0
	            	Iterator it;
	            	it = p.getSettlements().iterator();
			        while (it.hasNext()) {
			        	if(connT[pn] ==0)
			        		break;//avoid doing more checks if we already know we can't get somewhere
			        	SOCSettlement s = (SOCSettlement) it.next();
			        	if(!pathAndRoadsExist(start.getCoordinates(), s.getCoordinates(), p))
			        		connT[pn] = 0;
			        }
	            	//for each city in list of cities check if its connected, else set to 0
		            it = p.getCities().iterator();
		            while (it.hasNext()) {
			        	if(connT[pn] ==0)
			        		break;//avoid doing more checks if we already know we can't get somewhere
		                SOCCity c = (SOCCity) it.next();
		            	if(!pathAndRoadsExist(start.getCoordinates(), c.getCoordinates(), p))
			        		connT[pn] = 0;
		            }	
            	}
            }else{
            	connT[pn] = 0; //we want this value to be 0 for null players
            }
		}
		return connT;
	}
	
	/**
	 * Computes if the players have one of ther settlements isolated from the rest of their territory (i.e. there is no legal path to build roads to unite these)
	 * @return an array containing binary values, 0 for an isolated settlement, 1 otherwise (as the db variable is notIsolated)
	 */
	private int[] calculateAllIsolatedTerr(){
		SOCGame game = getGame();
		int[] notIsoT = new int[game.maxPlayers];
		Arrays.fill(notIsoT, 0);
		for (SOCPlayer p : game.getPlayers()) {
            String pName = p.getName();
            int pn = p.getPlayerNumber();
            if(pName!=null){
            	boolean isolated = false;
            	//out of all the settlements and cities, if there is one isolated
            	for(Object o : p.getPieces()){
            		int t = ((SOCPlayingPiece) o).getType();
            		if(t==SOCPlayingPiece.SETTLEMENT || t==SOCPlayingPiece.CITY){
            			if(!isNotIsolated(((SOCPlayingPiece) o).getCoordinates(), p))
            				isolated = true;
            		}
            	}
            	if(!isolated) //if there is none isolated than we set it 
            		notIsoT[pn] = 1;
            }else{
            	notIsoT[pn] = 0; //we want this value to be 0 for null players
            }
        }
		return notIsoT;
	}
	
	/**
	 * A DFS implementation that only checks if there is a path to another settlement/city not the length of the path
	 * and it doesn't memorise the edges taken. Similar to pathAndRoadsExist but this one has multiple possible goals 
	 * and doesn't care if there aren't any roads on the path.
	 * @param start the start location
	 * @return true if it found a path, false otherwise
	 */
	private boolean isNotIsolated(int start, SOCPlayer p){
		SOCGame game = getGame();
		SOCBoard bd = game.getBoard();
		Vector visited = new Vector();
		Stack stack = new Stack();
		stack.add(Integer.valueOf(start));
		while(!stack.empty()){
			Integer coord = (Integer) stack.pop();
			visited.add(coord);
			if(coord.intValue()!=start && p.hasSettlementOrCityAtNode(coord.intValue()))//stop when encountering one of our pieces on a node
				return true;
			Vector adjacents = bd.getAdjacentNodesToNode(coord.intValue());
			//check if adjacents can be accessed (i.e. the edge is empty or has one of our pieces)
			for(Object n : adjacents){
				int edge = bd.getEdgeBetweenAdjacentNodes(coord.intValue(), ((Integer)n).intValue());
				boolean wasVisited = false;
				//check if it has been visited before
				for(Object v : visited){
					if(((Integer)v).intValue() == ((Integer)n).intValue())
						wasVisited = true;
				}
				//check if the node is unnoccupied
				boolean nodeUnoccupied = true;
				for(Object sett : bd.getSettlements()){
					if(((SOCPlayingPiece)sett).getCoordinates()==((Integer)n).intValue())
						nodeUnoccupied = false; //we found a piece there
				}	
				for(Object city : bd.getCities()){
					if(((SOCPlayingPiece)city).getCoordinates()==((Integer)n).intValue())
						nodeUnoccupied = false; //we found a piece there
				}
				//check that the edge is unoccupied
				boolean edgeUnoccupied = true;
				for(Object road : bd.getRoads()){
					if(((SOCPlayingPiece)road).getCoordinates()==edge)
						edgeUnoccupied = false; //we found a piece there
				}
				//we are (either connected to the next node or the edge is empty) and the next node is either free or has one of our pieces
				if((p.hasRoadOrShipAtEdge(edge) || edgeUnoccupied) && (nodeUnoccupied || p.hasSettlementOrCityAtNode(((Integer)n).intValue()))){
					if(!wasVisited)
						stack.add(n);
				}
			}
		}
		return false;
	}
	
	/**
	 * A DFS implementation that only checks if there is a path to another settlement/city not the length of the path
	 * and it doesn't memorise the edges taken. Similar to isNotIsolated but this one has a specific finish.
	 * @param start
	 * @param finish
	 * @param p
	 * @return
	 */
	private boolean pathAndRoadsExist(int start, int finish, SOCPlayer p){
		SOCGame game = getGame();
		SOCBoard bd = game.getBoard();
		Vector visited = new Vector();
		Stack stack = new Stack();
		stack.add(Integer.valueOf(start));
		while(!stack.empty()){
			Integer coord = (Integer) stack.pop();
			visited.add(coord);
			if(coord.intValue()==finish)
				return true;
			Vector adjacents = bd.getAdjacentNodesToNode(coord.intValue());
			//check if adjacents can be accessed (i.e. the edge is empty or has one of our pieces)
			for(Object n : adjacents){
				int edge = bd.getEdgeBetweenAdjacentNodes(coord.intValue(), ((Integer)n).intValue());
				boolean wasVisited = false;
				//check if it was visited before
				for(Object v : visited){
					if(((Integer)v).intValue() == ((Integer)n).intValue())
						wasVisited = true;
				}
				//if we are connected to the next node add to stack
				if(p.hasRoadOrShipAtEdge(edge)){
					if(!wasVisited)
						stack.add(n);
				}
			}
		}
		return false;
	}
	
	/**
	 * Computes the longest road each player could build
	 * @return array containing values representing all max depth of roads for each player, based on their board position (player numbers) 
	 */
	private int[] calculateAllLongestPossibleRoads(){
		SOCGame game = getGame();
		SOCBoard bd = game.getBoard();
		int[] pathsLengths = new int[game.maxPlayers];
		Arrays.fill(pathsLengths, 0);
		for (SOCPlayer p : game.getPlayers()) {
            String pName = p.getName();
            int pn = p.getPlayerNumber();
            if(pName!=null){
            	for(Object o : p.getPieces()){
            		int t = ((SOCPlayingPiece) o).getType();
            		if(t==SOCPlayingPiece.SETTLEMENT || t==SOCPlayingPiece.CITY){
            			int depth = calcMaxDepthWithPath(((SOCPlayingPiece) o).getCoordinates(), p);
            			if(depth > pathsLengths[pn])
            				pathsLengths[pn] = depth;
            		}
            	}
            }
        }
		return pathsLengths;
	}
	
	/**
	 * A BFS implementation that only stops once it reaches 15 road pieces linked together or runs out of options.
	 * @return the max depth any path from start could reach
	 */
	private int calcMaxDepthWithPath(int start, SOCPlayer p){
		SOCGame game = getGame();
		SOCBoard bd = game.getBoard();
		Vector visited = new Vector();
		HashMap<Integer, Integer> coordToDepth = new HashMap<Integer, Integer>();
		Queue q = new Queue();
		q.put(Integer.valueOf(start));
		int depth =0;
		coordToDepth.put(Integer.valueOf(start), Integer.valueOf(depth));//start depth
		while(!q.empty()){
			if(depth == 15)
				break; //we don't have more than 15 roads anyway
			Integer coord = (Integer) q.get();
			//as we get one from the q we update the depth to the corresponding one in the map so we always keep track of the depth
			depth = coordToDepth.get(coord).intValue();
			visited.add(coord);
			Vector adjacents = bd.getAdjacentNodesToNode(coord.intValue());
			//add to map that tracks depth
			for(Object n : adjacents){
				coordToDepth.put((Integer) n, Integer.valueOf(depth+1)); //next level so + 1
			}
			
			//check if adjacents can be accessed (i.e. the edge is empty or has one of our pieces)
			for(Object n : adjacents){
				int edge = bd.getEdgeBetweenAdjacentNodes(coord.intValue(), ((Integer)n).intValue());
				boolean wasVisited = false;
				//check if it was visited before
				for(Object v : visited){
					if(((Integer)v).intValue() == ((Integer)n).intValue())
						wasVisited = true;
				}
				boolean unoccupied = true;
				for(Object piece : bd.getPieces()){
					if(((SOCPlayingPiece)piece).getCoordinates()==((Integer)n).intValue())
						unoccupied = false; //we found a piece there
				}	
				//we are either connected to the next node or the edge is not occupied
				if(p.hasRoadOrShipAtEdge(edge) || unoccupied){
					if(!wasVisited)
						q.put(n);
				}
			}
		}
		return depth;
	}
}
