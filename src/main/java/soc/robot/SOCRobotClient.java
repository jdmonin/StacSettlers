/**
 * Java Settlers - An online multiplayer version of the game Settlers of Catan
 * Copyright (C) 2003  Robert S. Thomas <thomas@infolab.northwestern.edu>
 * Portions of this file Copyright (C) 2007-2021 Jeremy D Monin <jeremy@nand.net>
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

import mcts.game.catan.belief.CatanFactoredBelief;
import soc.baseclient.ServerConnectInfo;
import soc.baseclient.SOCDisplaylessPlayerClient;

import soc.disableDebug.D;

import soc.game.SOCCity;
import soc.game.SOCGame;
import soc.game.SOCGameOption;
import soc.game.SOCGameOptionSet;
import soc.game.SOCPlayer;
import soc.game.SOCPlayingPiece;
import soc.game.SOCRoutePiece;
import soc.game.SOCSettlement;

import soc.message.*;

import soc.robot.stac.MCTSRobotBrain;
import soc.robot.stac.OriginalSSRobotBrain;
import soc.robot.stac.StacRobotBrain;
import soc.robot.stac.StacRobotBrainFlatMCTS;
import soc.robot.stac.StacRobotBrainRandom;
import soc.robot.stac.StacRobotDeclarativeMemory;
import soc.robot.stac.StacRobotType;
import soc.robot.stac.flatmcts.FlatMctsRewards;
import soc.robot.stac.negotiationlearning.LearningNegotiator;

import soc.server.SOCServer;
import soc.server.genericServer.Server;
import soc.server.genericServer.StringServerSocket;

import soc.util.CappedQueue;
import soc.util.CutoffExceededException;
import soc.util.DebugRecorder;
import soc.util.DeepCopy;
import soc.util.SOCFeatureSet;
import soc.util.SOCRobotParameters;
import soc.util.Version;

import supervised.main.BayesianSupervisedLearner;
import simpleDS.learning.SimpleAgent;

import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;

import java.net.Socket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Vector;


/**
 * This is a robot client that can play Settlers of Catan.
 *<P>
 * When ready, call {@link #init()} to start the bot's threads and connect to the server.
 * (Built-in bots should set {@link #printedInitialWelcome} beforehand to reduce console clutter.)
 * Once connected, messages from the server are processed in {@link #treat(SOCMessage)}.
 * For each game this robot client plays, there is a {@link SOCRobotBrain}.
 *<P>
 * The built-in robots must be the same version as the server, to simplify things.
 * Third-party bots might be based on this code and be other versions, to simplify their code.
 *<P>
 * The {@link soc.message.SOCImARobot IMAROBOT} connect message gives the bot's class name and
 * a required security cookie, which is passed into the robot client constructor and which must
 * match the server's generated cookie. You can set the server's cookie by setting the
 * server's {@code jsettlers.bots.cookie} parameter, or view it by setting {@code jsettlers.bots.showcookie},
 * when starting the server.
 *<P>
 * Once a bot has connected to the server, it waits to be asked to join games via
 * {@link SOCBotJoinGameRequest BOTJOINREQUEST} messages. When it receives that
 * message type, the bot replies with {@link SOCJoinGame JOINGAME} and the server
 * responds with {@link SOCJoinGameAuth JOINGAMEAUTH}. That message handler creates
 * a {@link SOCRobotBrain} to play the game it is joining.
 *
 *<H4>Debugging</H4>
 * Several bot debug messages are available by sending text messages from other players
 * with certain keywords. See {@link #handleGAMETEXTMSG_debug(SOCGameTextMsg)} for details.
 *
 *<H4>Third-Party Bots</H4>
 * Third-party robot clients can be built from scratch, or extend this class and/or {@link SOCRobotBrain}.
 * If extending this class, please remember to:
 *<UL>
 * <LI> Update {@link #rbclass} value before calling {@link #init()}
 * <LI> Override {@link #createBrain(SOCRobotParameters, SOCGame, CappedQueue)}
 *      to provide your subclass of {@link SOCRobotBrain}
 * <LI> Override {@link #buildClientFeats()} if your bot's optional client features differ from the standard bot
 *</UL>
 * See {@link soc.robot.sample3p.Sample3PClient} for a trivial example subclass.
 *
 *<H4>I18N</H4>
 * The bot ignores the contents of all {@link SOCGameServerText} messages and has no locale.
 * If robot debug commands ({@link SOCGameTextMsg}) are sent to the bot, its responses to the server are in English.
 *
 * @author Robert S Thomas
 */
public class SOCRobotClient extends SOCDisplaylessPlayerClient
{
    private SOCRobotFactory factory;

    /**
     * Information that pass on from one STAC robot brain to its next incarnation, 
     * ie to the brain created for the next game (meant for sequences of games in a simulation).
     */
    private HashMap persistentBrainInformation;

    /**
     * Output stream for the learned distribution of strategies.
     */
    public BufferedWriter strategiesOut;

    /**
     * constants for debug recording
     */
    public static final String CURRENT_PLANS = "CURRENT_PLANS";
    public static final String CURRENT_RESOURCES = "RESOURCES";

    public static boolean saved = false;//for debugging purposes; remove later (please ignore for now)

    /**
     * For server testing, system property {@code "jsettlers.bots.test.quit_at_joinreq"} to
     * randomly disconnect from the server when asked to join a game. If set, value is
     * the disconnect percentage 0-100.
     * @since 2.0.00
     */
    public static final String PROP_JSETTLERS_BOTS_TEST_QUIT_AT_JOINREQ = "jsettlers.bots.test.quit_at_joinreq";

    /**
     * For debugging feedback, hint text to remind user if debug recorder isn't on.
     * @since 2.0.00
     */
    private static final String HINT_SEND_DEBUG_ON_FIRST = "Debug recorder isn't on. Send :debug-on command first";

    /**
     * For server testing, random disconnect percentage from property
     * {@link #PROP_JSETTLERS_BOTS_TEST_QUIT_AT_JOINREQ}. Defaults to 0.
     * @since 2.0.00
     */
    private static int testQuitAtJoinreqPercent = 0;

    /**
     * For debugging/regression testing, randomly pause responding
     * for several seconds, to simulate a "stuck" robot brain.
     *<P>
     *<b>Note:</b> This debugging tool is not scalable to many simultaneous games,
     * because it delays all messages, not just ones for a specific game / brain,
     * and it won't be our turn in each of those games.
     *<P>
     * Because of the limited scope, currently there is no way to enable this
     * debug flag at runtime; the value must be edited here in the source.
     *
     * @see #DEBUGRANDOMPAUSE_FREQ
     * @see #debugRandomPauseActive
     * @since 1.1.11
     */
    protected static boolean debugRandomPause = false;  // set true to use this debug type

    /**
     * Is {@link #debugRandomPause} currently in effect for this client?
     * If so, this flag becomes {@code true} while pausing.
     * When true, store messages into {@link #debugRandomPauseQueue} instead of
     * sending them to {@link #robotBrains} immediately.
     * The pause goes on until {@link #debugRandomPauseUntil} arrives
     * and then {@code debugRandomPauseActive} becomes {@code false}
     * until the next random float below {@link #DEBUGRANDOMPAUSE_FREQ}.
     * This is all handled within {@link #treat(SOCMessage)}.
     * @since 1.1.11
     */
    protected boolean debugRandomPauseActive = false;

    /**
     * When {@link #debugRandomPauseActive} is true, store incoming messages
     * from the server into this queue until {@link #debugRandomPauseUntil}.
     * Initialized in {@link #treat(SOCMessage)}.
     * @since 1.1.11
     */
    protected Vector<SOCMessage> debugRandomPauseQueue = null;

    /**
     * When {@link #debugRandomPauseActive} is true, resume at this time;
     * same format as {@link System#currentTimeMillis()}.
     * @see #DEBUGRANDOMPAUSE_SECONDS
     * @since 1.1.11
     */
    protected long debugRandomPauseUntil;

    /**
     * When {@link #debugRandomPause} is true but not {@link #debugRandomPauseActive},
     * frequency of activating it; checked for each non-{@link SOCGameTextMsg}
     * and non-{@link SOCGameServerText} message received during our own turn.
     * Default is 0.04 (4%).
     * @since 1.1.11
     */
    protected static final double DEBUGRANDOMPAUSE_FREQ = .04;  // 4%

    /**
     * When {@link #debugRandomPauseActive} is activated, pause this many seconds
     * before continuing. Default is 12.
     * @see #debugRandomPauseUntil
     */
    protected static final int DEBUGRANDOMPAUSE_SECONDS = 12;

    /**
     * Robot class, to be reported to the server when connecting and
     * sending our {@link SOCImARobot} message. Defaults to
     * {@link SOCImARobot#RBCLASS_BUILTIN}: Third-party bots
     * should update this field before calling {@link #init()}.
     * @since 2.0.00
     */
    protected String rbclass = SOCImARobot.RBCLASS_BUILTIN;

    /**
     * Features supported by this built-in JSettlers robot client.
     * Initialized in {@link #init()}.
     * @since 2.0.00
     */
    protected SOCFeatureSet cliFeats;

    // Note: v2.2.00 moved the security cookie field to serverConnectInfo.robotCookie

    /**
     * the thread that reads incoming messages
     */
    private Thread readerRobot;

    /**
     * the current robot parameters for robot brains
     * @see #handleUPDATEROBOTPARAMS(SOCUpdateRobotParams)
     */
    protected SOCRobotParameters currentRobotParameters;

    /**
     * the robot's "brains", 1 for each game this robot is currently playing.
     * @see SOCDisplaylessPlayerClient#games
     */
    protected Hashtable<String, SOCRobotBrain> robotBrains = new Hashtable<String, SOCRobotBrain>();

    /**
     * the message queues for the different brains
     */
    protected Hashtable<String, CappedQueue<SOCMessage>> brainQs = new Hashtable<String, CappedQueue<SOCMessage>>();

    /**
     * a table of requests from the server to sit at games
     */
    private Hashtable<String, Integer> seatRequests = new Hashtable<String, Integer>();

    /**
     * Options for all games on the server we've been asked to join.
     * Some games may have no options, so will have no entry here,
     * although they will have an entry in {@link #games} once joined.
     * Key = game name.
     *<P>
     * Entries are added in {@link #handleBOTJOINGAMEREQUEST(SOCBotJoinGameRequest)}.
     * Since the robot and server are the same version, the
     * set of "known options" will always be in sync.
     */
    protected Hashtable<String, SOCGameOptionSet> gameOptions = new Hashtable<>();

    /**
     * number of games this bot has played
     */
    protected int gamesPlayed;

    /**
     * number of games finished
     */
    protected int gamesFinished;

    /**
     * number of games this bot has won
     */
    protected int gamesWon;

    /**
     * number of clean brain kills
     */
    protected int cleanBrainKills;

    /**
     * start time
     */
    protected final long startTime;

//    /**
//     * used to maintain connection
//     */
//    SOCRobotResetThread resetThread;
// not used ---MD

    /**
     * Have we printed the initial welcome message text from server?
     * Suppress further ones (disconnect-reconnect).
     *<P>
     * Can also set this {@code true} before calling {@link #init()}
     * to avoid printing the initial welcome.
     *
     * @since 1.1.06
     */
    public boolean printedInitialWelcome = false;

    // HWU negotiators (their states should persist across games)

    /** Supervised learning model for trade negotiation */ 
    BayesianSupervisedLearner sltrader = null;
    
    /** MDP learning model for trade negotiation */
    LearningNegotiator mdp_negotiator = null;
    
    /** Deep reinforcement learning model for trade negotiation */ 
    SimpleAgent deeptrader = null;

    /**
     * Constructor for a robot which will connect to a TCP or local server.
     * Does not actually connect here: Call {@link #init()} when ready.
     *
     * @param factory a RobotFactory to generate the robot-brain
     * @param sci  Server connect info (TCP or local) with {@code robotCookie}; not {@code null}
     * @param nn nickname for robot
     * @param pw Optional password for robot, or {@code null}
     * @throws IllegalArgumentException if {@code sci == null}
     * @since 2.2.00
     */
    public SOCRobotClient(SOCRobotFactory factory, final ServerConnectInfo sci, final String nn, final String pw)
        throws IllegalArgumentException
    {
        this(factory, sci, nn, pw, null);
    }

    public SOCRobotClient(SOCRobotFactory factory, final ServerConnectInfo sci, final String nn, final String pw, SimpleAgent deeptrader)
        throws IllegalArgumentException
    {
        super(sci, false);

    	this.factory = factory;
        gamesPlayed = 0;
        gamesFinished = 0;
        gamesWon = 0;
        cleanBrainKills = 0;
        startTime = System.currentTimeMillis();
        nickname = nn;
        password = pw;

        String val = System.getProperty(PROP_JSETTLERS_BOTS_TEST_QUIT_AT_JOINREQ);
        if (val != null)
            try
            {
                testQuitAtJoinreqPercent = Integer.parseInt(val);
            }
            catch (NumberFormatException e) {}

        if (nn.startsWith("Deep")) {
        	this.deeptrader = deeptrader;
        }

        //THIS SHOULD REALLY BE HANDLED AS PART OF THE PERSISTENT INFORMATION IN THE ROBOT BRAIN
//        try {
//            Date runStartDate = new Date();
//            String ds = "_" + runStartDate.toString().replace(':','_').replace(' ','_');
//            strategiesOut = new BufferedWriter(new FileWriter(new File("Q-values" + ds + ".txt")));
//            //write the column headers
//            String outString = "game number" + "\t" + 
//                    "won" + "\t" +
////                    strategies[0][0] + ", " + strategies[0][1] + "\t" +
////                    strategies[1][0] + ", " + strategies[1][1] + "\t" +
////                    strategies[2][0] + ", " + strategies[2][1] + "\t" +
////                    strategies[3][0] + ", " + strategies[3][1] + "\t" +
//                    "strategy 0" + "\t" + "strategy 1" + "\t" + "strategy 2" + "\t" + "strategy 3" + "\t" +
//                    "Sum";
//            strategiesOut.write(outString);
//            strategiesOut.newLine();
//            strategiesOut.flush();
//
//        } catch (IOException ex) {
//            Logger.getLogger(StacRobotBrain.class.getName()).log(Level.SEVERE, null, ex);
//        }
    }

    /**
     * Constructor for a robot which will connect to the specified host, on the specified port.
     * Does not actually connect here: Call {@link #init()} when ready.
     *<P>
     * This deprecated constructor is kept only for compatibility with third-party bot clients.
     *
     * @param factory a RobotFactory to generate the robot-brain
     * @param h  host
     * @param p  port
     * @param nn nickname for robot
     * @param pw password for robot
     * @param co  cookie for robot connections to server
     * @deprecated In v2.2.00 and newer, use the {@link #SOCRobotClient(ServerConnectInfo, String, String)}
     *     constructor instead:<BR>
     *     {@code new SOCRobotClient(new ServerConnectInfo(h, p, co), nn, pw);}
     */
    public SOCRobotClient(SOCRobotFactory factory, final String h, final int p, final String nn, final String pw, final String co)
    {
        this(factory, new ServerConnectInfo(h, p, co), nn, pw);
    }

    /**
     * Initialize the robot player; connect to server and send first messages
     * including our version, features from {@link #buildClientFeats()}, and {@link #rbclass}.
     * If fails to connect, sets {@link #ex} and prints it to {@link System#err}.
     */
    public void init()
    {
        try
        {
            if (serverConnectInfo.stringSocketName == null)
            {
                sock = new Socket(serverConnectInfo.hostname, serverConnectInfo.port);
                sock.setSoTimeout(300000);
                in = new DataInputStream(sock.getInputStream());
                out = new DataOutputStream(sock.getOutputStream());
            }
            else
            {
                sLocal = StringServerSocket.connectTo(serverConnectInfo.stringSocketName);
            }
            connected = true;
            readerRobot = new Thread(this);
            readerRobot.start();

            Server.trackThread(readerRobot, null);

            if (cliFeats == null)
            {
                cliFeats = buildClientFeats();
                // subclass or third-party bot may override: must check result
                if (cliFeats == null)
                    throw new IllegalStateException("buildClientFeats() must not return null");
            }

            //resetThread = new SOCRobotResetThread(this);
            //resetThread.start();
            put(SOCVersion.toCmd
                (Version.versionNumber(), Version.version(), Version.buildnum(), cliFeats.getEncodedList(), null));
            put(SOCImARobot.toCmd(nickname, serverConnectInfo.robotCookie, rbclass));
        }
        catch (Exception e)
        {
            ex = e;
            System.err.println("Could not connect to the server: " + ex);
        }
    }

    /**
     * Disconnect and then try to reconnect. Sends the same messages as {@link #init()}.
     * If the reconnect fails, will retry a maximum of 3 times.
     * If those attempts all fail, {@link #connected} will be false and {@link #ex} will be set.
     * Otherwise when method returns, {@link #connected} is true and {@code ex} is null.
     */
    public void disconnectReconnect()
    {
        D.ebugPrintlnINFO("(*)(*)(*)(*)(*)(*)(*) disconnectReconnect()");
        ex = null;

        for (int attempt = 3; attempt > 0; --attempt)
        {
            try
            {
                connected = false;
                if (serverConnectInfo.stringSocketName == null)
                {
                    sock.close();
                    sock = new Socket(serverConnectInfo.hostname, serverConnectInfo.port);
                    in = new DataInputStream(sock.getInputStream());
                    out = new DataOutputStream(sock.getOutputStream());
                }
                else
                {
                    sLocal.disconnect();
                    sLocal = StringServerSocket.connectTo(serverConnectInfo.stringSocketName);
                }
                connected = true;
                readerRobot = new Thread(this);
                readerRobot.start();

                Server.trackThread(readerRobot, null);

                //resetThread = new SOCRobotResetThread(this);
                //resetThread.start();
                put(SOCVersion.toCmd
                    (Version.versionNumber(), Version.version(), Version.buildnum(), cliFeats.getEncodedList(), null));
                put(SOCImARobot.toCmd(nickname, serverConnectInfo.robotCookie, SOCImARobot.RBCLASS_BUILTIN));

                break;  // <--- Exit attempt-loop ---
            }
            catch (Exception e)
            {
                ex = e;
                // Comment this out - happens predictably at the end of a test run
                //System.err.println("disconnectReconnect error: " + ex);
                //if (attempt > 0)
                //    System.err.println("-> Retrying");
            }
        }

        if (! connected)
        {
            System.err.println("-> Giving up");

            // Couldn't reconnect. Shut down active games' brains.
            for (SOCRobotBrain rb : robotBrains.values())
                rb.kill();
        }
    }

    /**
     * Build the set of optional client features this bot supports, to send to the server.
     * ({@link SOCFeatureSet#CLIENT_6_PLAYERS}, etc.)
     *<P>
     * Third-party subclasses should override this if their features are different.
     *<P>
     * The built-in robots' client features are currently:
     *<UL>
     * <LI>{@link SOCFeatureSet#CLIENT_6_PLAYERS}
     * <LI>{@link SOCFeatureSet#CLIENT_SEA_BOARD}
     * <LI>{@link SOCFeatureSet#CLIENT_SCENARIO_VERSION} = {@link Version#versionNumber()}
     *</UL>
     * For robot debugging and testing, will also add a feature from
     * {@link SOCDisplaylessPlayerClient#PROP_JSETTLERS_DEBUG_CLIENT_GAMEOPT3P} if set,
     * and create that Known Option with {@link SOCGameOption#FLAG_3RD_PARTY} if not already created:
     * Calls {@link SOCGameOptionSet#addKnownOption(SOCGameOption)}.
     *<P>
     * Called from {@link #init()}.
     *
     * @return  This bot's set of implemented optional client features, if any, or an empty set (not {@code null})
     * @since 2.0.00
     */
    protected SOCFeatureSet buildClientFeats()
    {
        SOCFeatureSet feats = new SOCFeatureSet(false, false);
        feats.add(SOCFeatureSet.CLIENT_6_PLAYERS);
        feats.add(SOCFeatureSet.CLIENT_SEA_BOARD);
        feats.add(SOCFeatureSet.CLIENT_SCENARIO_VERSION, Version.versionNumber());

        String gameopt3p = System.getProperty(SOCDisplaylessPlayerClient.PROP_JSETTLERS_DEBUG_CLIENT_GAMEOPT3P);
        if (gameopt3p != null)
        {
            gameopt3p = gameopt3p.toUpperCase(Locale.US);
            feats.add("com.example.js." + gameopt3p);

            if (null == knownOpts.getKnownOption(gameopt3p, false))
            {
                knownOpts.addKnownOption(new SOCGameOption
                    (gameopt3p, 2000, Version.versionNumber(), false,
                     SOCGameOption.FLAG_3RD_PARTY | SOCGameOption.FLAG_DROP_IF_UNUSED,
                     "Client test 3p option " + gameopt3p));
                // similar code is in SOCPlayerClient constructor
            }
        }

        return feats;
    }

    /**
     * Factory method for creating a new {@link SOCRobotBrain}.
     *<P>
     * Third-party clients can override this method to use this client with
     * different robot brain subclasses.
     *
     * @param params  the robot parameters to use
     * @param ga  the game in which the brain will play
     * @param mq  the inbound message queue for this brain from the client
     * @return the newly created brain
     * @since 2.0.00
     */
    public SOCRobotBrain createBrain
        (final SOCRobotParameters params, final SOCGame ga, final CappedQueue<SOCMessage> mq)
    {
        if (factory != null)
            return factory.getRobot(this, params, ga, mq);
        else
            return new SOCRobotBrainImpl(this, params, ga, mq);
    }

    /**
     * Treat the incoming messages.
     * Messages of unknown type are ignored. All {@link SOCGameServerText} are ignored.
     * ({@code mes} will be null from {@link SOCMessage#toMsg(String)}).
     *<P>
     *<B>Note:</B> If a message doesn't need any robot-specific handling,
     * and doesn't appear as a specific case in this method's switch,
     * this method calls {@link SOCDisplaylessPlayerClient#treat(SOCMessage)} for it.
     *
     * @param mes    the message
     */
    @Override
    public void treat(SOCMessage mes)
    {
        if (mes == null)
            return;  // Message syntax error or unknown type

        // Using debugRandomPause?
        if (debugRandomPause && (! robotBrains.isEmpty())
            && (mes instanceof SOCMessageForGame)
            && ! (mes instanceof SOCGameTextMsg)
            && ! (mes instanceof SOCGameServerText)
            && ! (mes instanceof SOCTurn))
        {
            final String ga = ((SOCMessageForGame) mes).getGame();
            if (ga != null)
            {
                SOCRobotBrain brain = robotBrains.get(ga);
                if (brain != null)
                {
                    if (! debugRandomPauseActive)
                    {
                        // random chance of doing so
                        if ((Math.random() < DEBUGRANDOMPAUSE_FREQ)
                            && ((debugRandomPauseQueue == null)
                                || (debugRandomPauseQueue.isEmpty())))
                        {
                            SOCGame gm = games.get(ga);
                            final int cpn = gm.getCurrentPlayerNumber();
                            SOCPlayer rpl = gm.getPlayer(nickname);
                            if ((rpl != null) && (cpn == rpl.getPlayerNumber())
                                && (gm.getGameState() >= SOCGame.ROLL_OR_CARD))
                            {
                                // we're current player, pause us
                                debugRandomPauseActive = true;
                                debugRandomPauseUntil = System.currentTimeMillis() + (1000L * DEBUGRANDOMPAUSE_SECONDS);
                                if (debugRandomPauseQueue == null)
                                    debugRandomPauseQueue = new Vector<SOCMessage>();
                                System.err.println("L379 -> do random pause: " + nickname);
                                sendText(gm,
                                    "debugRandomPauseActive for " + DEBUGRANDOMPAUSE_SECONDS + " seconds");
                            }
                        }
                    }
                }
            }
        }

        if (debugRandomPause && debugRandomPauseActive)
        {
            if ((System.currentTimeMillis() < debugRandomPauseUntil)
                && ! (mes instanceof SOCTurn))
            {
                // time hasn't arrived yet, and still our turn:
                //   Add message to queue (even non-game and SOCGameTextMsg)
                debugRandomPauseQueue.addElement(mes);

                return;  // <--- Early return: debugRandomPauseActive ---
            }

            // time to resume the queue
            debugRandomPauseActive = false;
            while (! debugRandomPauseQueue.isEmpty())
            {
                // calling ourself is safe, because
                //  ! queue.isEmpty; thus won't decide
                //  to set debugRandomPauseActive=true again.
                treat(debugRandomPauseQueue.firstElement());
                debugRandomPauseQueue.removeElementAt(0);
            }

            // Don't return from this method yet,
            // we still need to process mes.
        }

        if ((debugTraffic || D.ebugIsEnabled())
            && ! ((mes instanceof SOCServerPing) && (nextServerPingExpectedAt != 0)
                  && (Math.abs(System.currentTimeMillis() - nextServerPingExpectedAt) <= 66000)))
                          // within 66 seconds of the expected time; see displaylesscli.handleSERVERPING
        {
            soc.debug.D.ebugPrintlnINFO("IN - " + nickname + " - " + mes);
        }

        try
        {
            switch (mes.getType())
            {
            /**
             * status message
             */
            case SOCMessage.STATUSMESSAGE:
                handleSTATUSMESSAGE((SOCStatusMessage) mes);
                break;

            /**
             * admin ping
             */
            case SOCMessage.ADMINPING:
                handleADMINPING((SOCAdminPing) mes);
                break;

            /**
             * admin reset
             */
            case SOCMessage.ADMINRESET:
                handleADMINRESET((SOCAdminReset) mes);
                break;

            /**
             * update the current robot parameters
             */
            case SOCMessage.UPDATEROBOTPARAMS:
                handleUPDATEROBOTPARAMS((SOCUpdateRobotParams) mes);
                break;

            /**
             * join game authorization
             */
            case SOCMessage.JOINGAMEAUTH:
                handleJOINGAMEAUTH((SOCJoinGameAuth) mes, (sLocal != null));
                break;

            /**
             * game has been destroyed
             */
            case SOCMessage.DELETEGAME:
                handleDELETEGAME((SOCDeleteGame) mes);
                break;

            /**
             * list of game members
             */
            case SOCMessage.GAMEMEMBERS:
                handleGAMEMEMBERS((SOCGameMembers) mes);
                break;

            /**
             * game text message (bot debug commands)
             */
            case SOCMessage.GAMETEXTMSG:
                handleGAMETEXTMSG((SOCGameTextMsg) mes);
                break;

            /**
             * parse result
             */
            case SOCMessage.PARSERESULT:
                handlePARSERESULT((SOCParseResult) mes);

                break;

            /**
             * someone is sitting down
             */
            case SOCMessage.SITDOWN:
                handleSITDOWN((SOCSitDown) mes);
                break;

            /**
             * message that a general game is starting
             */
            case SOCMessage.STARTGAME:
                handleSTARTGAME((SOCStartGame) mes);

                break;

            /**
             * message that a stac game is starting
             */
            case SOCMessage.STACSTARTGAME:
                handleSTACSTARTGAME((StacStartGame) mes);
                break;

            /**
             * update the state of the game
             */
            case SOCMessage.GAMESTATE:
                handleGAMESTATE((SOCGameState) mes);
                break;

            /**
             * a player built something
             */
            case SOCMessage.PUTPIECE:
                handlePUTPIECE((SOCPutPiece) mes);
                break;

            /**
             * the server is requesting that we join a game
             */
            case SOCMessage.BOTJOINGAMEREQUEST:
                handleBOTJOINGAMEREQUEST((SOCBotJoinGameRequest) mes);
                break;

            /**
             * message that means the server wants us to leave the game
             */
            case SOCMessage.ROBOTDISMISS:
                handleROBOTDISMISS((SOCRobotDismiss) mes);
                break;

            /**
             * handle board reset (new game with same players, same game name, new layout).
             */
            case SOCMessage.RESETBOARDAUTH:
                handleRESETBOARDAUTH((SOCResetBoardAuth) mes);
                break;

            /**
             * generic "simple request" responses or announcements from the server.
             * Message type added 2013-02-17 for v1.1.18,
             * bot ignored these until 2015-10-10 for v2.0.00 SC_PIRI
             * and for PROMPT_PICK_RESOURCES from gold hex.
             */
            case SOCMessage.SIMPLEREQUEST:
                super.handleSIMPLEREQUEST(games, (SOCSimpleRequest) mes);
                handlePutBrainQ((SOCSimpleRequest) mes);
                break;

            /**
             * generic "simple action" announcements from the server.
             * Added 2013-09-04 for v1.1.19.
             */
            case SOCMessage.SIMPLEACTION:
                super.handleSIMPLEACTION(games, (SOCSimpleAction) mes);
                handlePutBrainQ((SOCSimpleAction) mes);
                break;

            /**
             * a special inventory item action: either add or remove,
             * or we cannot play our requested item.
             * Added 2013-11-26 for v2.0.00.
             */
            case SOCMessage.INVENTORYITEMACTION:
                {
                    final boolean isReject = super.handleINVENTORYITEMACTION
                        (games, (SOCInventoryItemAction) mes);
                    if (isReject)
                        handlePutBrainQ((SOCInventoryItemAction) mes);
                }
                break;

            /**
             * Special Item change announcements.
             * Added 2014-04-16 for v2.0.00.
             */
            case SOCMessage.SETSPECIALITEM:
                super.handleSETSPECIALITEM(games, (SOCSetSpecialItem) mes);
                handlePutBrainQ((SOCSetSpecialItem) mes);
                break;

            /** 
             * Game stats: Pass them to the brain to learn if it's so inclined
             * 
             */
            case SOCMessage.GAMESTATS:
                handleGenericMessage((SOCGameStats) mes);
                break;
             
            /**
             * handle clone of game data request.
             */
            case SOCMessage.GAMECOPY:
                handleGAMECOPY((SOCGameCopy) mes);
                break;

            /**
             * handle load game request.
             */
            case SOCMessage.LOADGAME:
                handleLOADGAME((SOCLoadGame) mes);
                break;
            
            /**
             * handle robot flag change message.
             */
            case SOCMessage.ROBOTFLAGCHANGE:
            	handleROBOTFLAGCHANGE ((SOCRobotFlag) mes);
            	break;
                        
            /**
             * handle robot flag change message.
             */
            case SOCMessage.COLLECTDATA:
            	handleCollectData((SOCCollectData) mes);
            	break;

            // These message types are handled entirely by SOCRobotBrain,
            // which will update game data and do any bot-specific tracking or actions needed:

            case SOCMessage.ACCEPTOFFER:
            case SOCMessage.CANCELBUILDREQUEST:  // current player has cancelled an initial settlement
            case SOCMessage.CHOOSEPLAYER:  // server wants our player to choose to rob cloth or rob resources from victim
            case SOCMessage.CHOOSEPLAYERREQUEST:
            case SOCMessage.CLEAROFFER:
            case SOCMessage.DEVCARDACTION:  // either draw, play, or add to hand, or cannot play our requested dev card
            case SOCMessage.DICERESULT:
            case SOCMessage.DISCARDREQUEST:
            case SOCMessage.BANKTRADE:
            case SOCMessage.MAKEOFFER:
            case SOCMessage.MOVEPIECE:   // move a previously placed ship; will update game data and player trackers
            case SOCMessage.MOVEROBBER:
            case SOCMessage.PLAYERELEMENT:
            case SOCMessage.PLAYERELEMENTS:  // apply multiple PLAYERELEMENT updates; added 2017-12-10 for v2.0.00
            case SOCMessage.REJECTOFFER:
            case SOCMessage.REPORTROBBERY:  // added 2021-01-05 for v2.4.50
            case SOCMessage.RESOURCECOUNT:
            case SOCMessage.TIMINGPING:  // server's 1x/second timing ping
            case SOCMessage.TURN:
                handlePutBrainQ((SOCMessageForGame) mes);
                break;

            // These message types are ignored by the robot client;
            // don't send them to SOCDisplaylessClient.treat:

            case SOCMessage.BCASTTEXTMSG:
            case SOCMessage.CHANGEFACE:
            case SOCMessage.CHANNELMEMBERS:
            case SOCMessage.CHANNELS:        // If bot ever uses CHANNELS, update SOCChannels class javadoc
            case SOCMessage.CHANNELTEXTMSG:
            case SOCMessage.DELETECHANNEL:
            case SOCMessage.GAMES:
            case SOCMessage.GAMESERVERTEXT:  // SOCGameServerText contents are ignored by bots
                                             // (but not SOCGameTextMsg, which is used solely for debug commands)
            case SOCMessage.JOINCHANNEL:
            case SOCMessage.JOINCHANNELAUTH:
            case SOCMessage.LEAVECHANNEL:
            case SOCMessage.NEWCHANNEL:
            case SOCMessage.NEWGAME:
            case SOCMessage.SETSEATLOCK:
                break;  // ignore this message type

            /**
             * Call SOCDisplaylessClient.treat for all other message types.
             * For types relevant to robots, it will update data from the message contents.
             * Other message types will be ignored.
             */
            default:
                super.treat(mes, true);
            }
        }
        catch (Throwable e)
        {
            System.err.println("SOCRobotClient treat ERROR - " + e + " " + e.getMessage());
            e.printStackTrace();
            while (e.getCause() != null)
            {
                e = e.getCause();
                System.err.println(" -> nested: " + e.getClass());
                e.printStackTrace();
            }
            System.err.println("-- end stacktrace --");
            System.out.println("  For message: " + mes);
        }
    }

	private void handleCollectData(SOCCollectData mes) {
        CappedQueue brainQ = (CappedQueue) brainQs.get(mes.getGame());

        if (brainQ != null)
        {
            try
            {
                brainQ.put(mes);
            }
            catch (CutoffExceededException exc)
            {
                D.ebugPrintlnINFO("CutoffExceededException" + exc);
            }
        }
		
	}

	//---MD Methods for handling messages for SAVE/LOAD function
    /**
     * updates the robot flag in the player object from the game object.
     * @param mes the received message
     */
    private void handleROBOTFLAGCHANGE(SOCRobotFlag mes){
    	SOCGame game = (SOCGame) games.get(mes.getGame());//in theory this should be the same object as the ones in the brain etc
    	SOCPlayer player = game.getPlayer(mes.getPlayerNumber());
		player.setRobotFlagUnsafe(mes.getFlag());
    }
    
    /**
     * Loads a previous game.
     * Reads the saved game and robot states (i.e. {@link SOCGame}, {@link SOCPlayer},an array of {@link SOCPlayerTracker}, 
     * {@link StacRobotDeclarativeMemory} and{@link StacRobotBrainInfo}). Then it follows through these steps(in this order):
     * <ul>
     * 	<li>Suspends the brain;
     * 	<li>Updates the new game obj with the old game name and old player names;
     * 	<li>Restores the reference to the correct game object for each player obj inside the players array contained by the game obj;
     * 	<li>Replaces the old game data with the cloned data inside the client's list of games;
     * 	<li>Update the reference to the brain inside each possibleCity for each cloned player tracker;
     * 	<li>Update each of the existing tracker in the brain from the corresponding cloned tracker;
     * 	<li>Update the reference in each existing tracker to the corresponding cloned player objects;
     * 	<li>Also update the parameters ourPlayerData/Tracker in the brain;
     * 	<li>Restore the game reference in the brain to the cloned one;
     *  <li>Update the brain state and parameters from the saved container({@link StacRobotBrainInfo});
     *  <li>Update the information in the declarative memory using the information inside the cloned one;
     *  <li>recreate/update DM/DialogueMgr/Negotiator;
     *  <li>Calculates some initial results for the MCTS logic, in case we are running simulations;
     *  <li>Wakes the brain up.
     * <ul>
     * 
     * NOTE: this piece of code looks horrible and is extremely brittle as it needs to handle the existing spaghetti code in the baseline; 
     * 		thus it needs to update all the references between the objects and update some of the information in a specific order (i.e. 
     * 		update all the information inside the brain first and then recreate/update DM/DialogueMgr/Negotiator)
     * @param mes the received message
     */
    private void handleLOADGAME(SOCLoadGame mes) {
        //get some required info from old state
        String prefix = mes.getFolder() + "/"; //the reason for doing this here is that when calling getDirectory the "/" is not added
    	String brainType = this.robotBrains.get(mes.getGame()).getClass().getName(); //get the brain type
    	SOCRobotBrain rb = (SOCRobotBrain) this.robotBrains.get(mes.getGame()); //get a reference to the brain
    	SOCGame originalGame = (SOCGame) games.get(mes.getGame()); //get a reference to the old game obj in order to get the right player names
    	int pn = rb.getOurPlayerData().getPlayerNumber(); //get this player's number
    	
    	//suspend brain
    	CappedQueue brainQ = (CappedQueue) brainQs.get(mes.getGame());
        if (brainQ != null)
        {
            try
            {
                brainQ.put(mes);
            }
            catch (CutoffExceededException exc)
            {
                D.ebugPrintlnINFO("CutoffExceededException" + exc);
            }
        }
        // and wait for it to be suspended
        while(!rb.isSuspended()){
        	try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
        }
    	
    	String fileName = prefix + pn + "_" + SOCGame.class.getName();
    	SOCGame gameClone = (SOCGame) DeepCopy.readFromFile(fileName); //read the SOCGame object
    	gameClone.setName(originalGame.getName()); //keep the old name for safety reasons
    	gameClone.updatePlayerNames(originalGame.getPlayerNames()); //keep the old player names
    	gameClone.resetTimes();
    	int n = gameClone.maxPlayers; //the number of players participating in the game
    	for(int i = 0; i < n; i++){
			SOCPlayer p = gameClone.getPlayer(i);
			if(i==pn){
				//make sure we are known as a robot so our turn can be ended
				p.setRobotFlagUnsafe(true);
				put(SOCRobotFlag.toCmd(originalGame.getName(), true, pn));
			}
			p.setGame(gameClone); //restore reference to this game in the player objects
		}
    	//replace the old game obj with the new one in this client's list of games
    	this.games.remove(originalGame.getName());
		this.games.put(gameClone.getName(), gameClone);
		
    	fileName = prefix + pn + "_" + ArrayList.class.getName();
    	ArrayList trackersList = (ArrayList) DeepCopy.readFromFile(fileName);  //read the SOCPlayerTrackers for this player
    	if (trackersList == null){//logic for handling the case one human player is replaced by a robot
    		for(int i=0; i < gameClone.maxPlayers; i++){
    			fileName = prefix + i + "_" + ArrayList.class.getName();
    			trackersList = (ArrayList) DeepCopy.readFromFile(fileName);
    			if(trackersList != null)
    				break;
    		}
    	}
    	//if at least one robot was playing in the original game use their trackers
    	if (trackersList != null){
	    	Object[] pt = trackersList.toArray();
			//update all playerTrackers in the brain (also extra logic for our playerData and tracker)
			for(int i = 0; i < n; i++){
				SOCPlayerTracker pti = (SOCPlayerTracker) pt[i];
				SOCPlayer pl = gameClone.getPlayer(i);
				// merge TODO: update the reference to the correct estimate factory in PossibleCities and PossibleSettlements objects
				//for (SOCPossibleCity posCity: pti.getPossibleCities().values())
				//	posCity.setTransientsAtLoad(pl, pti);

				//for (SOCPossibleSettlement posSettl : pti.getPossibleSettlements().values())
				//	posSettl.setTransientsAtLoad(pl, pti);

				SOCPlayerTracker tracker = (SOCPlayerTracker) rb.getPlayerTrackers()[i];
				tracker.partialUpdateFromTracker(pti); //update the playerTracker from the cloned one
				tracker.setPlayer(gameClone.getPlayer(i)); //restore the references to the correct player objects
				
				//if this is the current robot's tracker extra logic is required
				if (i == pn){
					rb.getOurPlayerTracker().partialUpdateFromTracker(pti);// update ourPlayerTracker
					SOCPlayer ourPlayer = gameClone.getPlayer(i);
					rb.getOurPlayerTracker().setPlayer(ourPlayer);//don't forget the player object in this playerTracker
					rb.setOurPlayerData(ourPlayer); // also ourPlayerData
				}
			}
    	}else{//only human players were participating in the original game, need to recreate the trackers
    		//first reestablish the links
    		for(int i = 0; i < gameClone.maxPlayers ; i++){
    			SOCPlayerTracker pt = (SOCPlayerTracker) rb.getPlayerTrackers()[i];
    			SOCPlayer player = gameClone.getPlayer(i);
    			pt.setPlayer(player);
    			if (i == pn){
    				rb.getOurPlayerTracker().setPlayer(player);
    				rb.setOurPlayerData(player);
    				rb.getOurPlayerTracker().reinitTracker();
    			}
    			//and reinitialise the trackers
    			pt.reinitTracker();
    		}
    		
    		//try to recreate the treeMaps of possible pieces from the game object, by re-tracking everything;
    		//start with settlements;
    		for(SOCSettlement o : gameClone.getBoard().getSettlements())
    			rb.trackNewSettlement(o, false);
    		//continue with roads;
    		for(SOCRoutePiece o : gameClone.getBoard().getRoadsAndShips())
    			rb.trackNewRoadOrShip(o, false);
    		//finish with cities
    		for(SOCCity o : gameClone.getBoard().getCities())
    			rb.trackNewCity(o, false);
    		//finally recalculate all ETA's
    		for(int i = 0; i < gameClone.maxPlayers ; i++){
    			rb.getPlayerTrackers()[i].recalculateAllETAs();
    			if (i == pn){
    				rb.getOurPlayerTracker().recalculateAllETAs();
    			}
    		}
    	}
		rb.game = gameClone; //for SOCRobotBrain we need to reference to the correct game object inside the brain
    	fileName = prefix + pn + "_" + StacRobotBrainInfo.class.getName();
    	StacRobotBrainInfo brainInfoClone = (StacRobotBrainInfo) DeepCopy.readFromFile(fileName); //read the brain info bytes
    	if(brainInfoClone != null){ //by ignoring this step I expect this loading mechanism to work only in a fraction of cases for now;
    		brainInfoClone.waitingForGameState = false; //there is absolutely no way we were waiting for the game state when saving  (how could this happen??)
    	}else{
    		//else we need to try and recreate the brain info from the game object;
    		brainInfoClone = new StacRobotBrainInfo(gameClone, pn);
    	}
    	rb.partialUpdateFromInfo(brainInfoClone); //update the brain's state and parameters
    	
    	//update dialogue mgr and declarative memory only if it is a Stac or a StacRandom brain type
    	if(brainType.equals(StacRobotBrain.class.getName()) || brainType.equals(StacRobotBrainRandom.class.getName())
    			|| brainType.equals(StacRobotBrainFlatMCTS.class.getName()) || brainType.equals(MCTSRobotBrain.class.getName())){
			fileName = prefix + pn + "_" + StacRobotDeclarativeMemory.class.getName();
	    	StacRobotDeclarativeMemory memoryClone = (StacRobotDeclarativeMemory) DeepCopy.readFromFile(fileName);  //read the DeclarativeMemory object
	    	if(memoryClone != null)
	    		((StacRobotBrain) rb).getMemory().partialUpdateFromMemory(memoryClone);//update the memory's info
	    	else{
	    		((StacRobotBrain) rb).getMemory().reinitMemory();//try and reinit the memory
	    	}
	    	if(brainType.equals(MCTSRobotBrain.class.getName())) {
	    		fileName = prefix + pn + "_" + CatanFactoredBelief.class.getName();
	    		CatanFactoredBelief beliefClone = (CatanFactoredBelief) DeepCopy.readFromFile(fileName);
		    	if(beliefClone != null)
		    		((MCTSRobotBrain) rb).setBelief(beliefClone);//update the memory's info
		    	else
		    		((MCTSRobotBrain) rb).reinitBeliefModel();
	    	}
	    	
	    	rb.startGameChat();//also announce chat participation again
    	}
    	
    	rb.recreateDM();       	//after finishing with updating all the info in the brain, recreate the DM from the new info
    	rb.negotiator.update();	//update the negotiator from the new info in the brain

    	//final piece of code required by the MCTS logic to gather results during simulations (TODO: (maybe) in here add some logic for shuffling the dev card stack and redistribute dev cards)
    	if(brainType.equals(StacRobotBrainRandom.class.getName()) && ((StacRobotBrainRandom)rb).getResults()!=null){
    		FlatMctsRewards stats = ((StacRobotBrainRandom)rb).getResults();
    		for(int i= 0; i< 4; i++){
    			stats.getInitialVPs()[i] = gameClone.getPlayer(i).getPublicVP(); //vp when loading
    			//recalculate estimates and ETW
    			SOCBuildingSpeedEstimate estimator = rb.getEstimator();
    			estimator.recalculateEstimates(gameClone.getPlayer(i).getNumbers());
                int[] speeds = estimator.getEstimatesFromNothingFast(gameClone.getPlayer(i).getPortFlags());
                int totalSpeed = 0;

                for (int j = SOCBuildingSpeedEstimate.MIN;
                        j < SOCBuildingSpeedEstimate.MAXPLUSONE; j++)
                {
                    totalSpeed += speeds[j];
                }
                
                stats.getInitialETBs()[i] = totalSpeed;
    			
//    			rb.getEstimator().recalculateEstimates(gameClone.getPlayer(i).getNumbers(), gameClone.getBoard().getRobberHex());
    			((SOCPlayerTracker) rb.getPlayerTrackers()[i]).recalcWinGameETA();
    			stats.getInitialETWs()[i] = ((SOCPlayerTracker) rb.getPlayerTrackers()[i]).getWinGameETA(); //etw when loading
    			stats.setMaxTotalRssBlocked(gameClone.getTotalPossibleBlockedRss()); //how many rss can be blocked per turn
    		}
    	}
    		
    	if(brainType.equals(MCTSRobotBrain.class.getName())) {
    		((MCTSRobotBrain)rb).generateBoard();
    	}
    	
    	//wake the brain up
    	rb.awaken();
//    	System.out.println("Robot Client "+ pn +": received load game request"); //for quick debugging
	}
    
    /**
     * Saves all the current game information and required data for recreating this robot's state:
     * <ul>
     * 	<li>Suspends the brain;
     * 	<li>{@link SOCGame} object (including the {@link SOCPlayer} objects);
     * 	<li>an array of {@link SOCPlayerTracker} (from the brain);
     * 	<li>{@link StacRobotDeclarativeMemory} if its a stac type brain;
     * 	<li>{@link StacRobotBrainInfo} which is the container with the brain state saved;
     * 	<li>Wakes the brain up.
     * <ul>
     * @param mes the received message
     */
	private void handleGAMECOPY(SOCGameCopy mes) {
		//get some information required for the saving procedure
		SOCRobotBrain rb = (SOCRobotBrain) this.robotBrains.get(mes.getGame());
		int pn = rb.getOurPlayerData().getPlayerNumber(); //get the player number
		if(pn != mes.getPlayerNumber()){ //if we aren't the player that initiated the save, then execute the safe synchronized saving method 
			//suspend brain
			CappedQueue brainQ = (CappedQueue) brainQs.get(mes.getGame());
	        if (brainQ != null)
	        {
	            try
	            {
	                brainQ.put(mes);
	            }
	            catch (CutoffExceededException exc)
	            {
	                D.ebugPrintlnINFO("CutoffExceededException" + exc);
	            }
	        }
	        //wait for brain to be suspended
	        while(!rb.isSuspended()){
	        	try {
					Thread.sleep(20);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
	        }
	        //now do the actual copying
	        unsynckedGameCopy(mes);
	    	
	        //wake the brain up
	        rb.awaken();
		}//otherwise do nothing as the brain would have taken care of the saving at the right moment; it would be too late to save in here
//		System.out.println("Robot Client " + pn + ": received gameCopy request"); //for quick debugging
	}
	/**
	 * Contains the actual logic for handling the game saving, but without the check for the player number and without suspending the brain
	 * @param mes the information regarding the folder locations
	 */
	public void unsynckedGameCopy(SOCGameCopy mes){
		//get some information required for the saving procedure
		String brainType = this.robotBrains.get(mes.getGame()).getClass().getName(); //get the robot brain type
		SOCRobotBrain rb = (SOCRobotBrain) this.robotBrains.get(mes.getGame());
		rb.saved = true;//block any further saves
		int pn = rb.getOurPlayerData().getPlayerNumber(); //get the player number
		SOCGame game = (SOCGame) this.games.get(mes.getGame());
		int n = game.maxPlayers;
		
		//clone an array that contains the PlayerTracker for each player in the game from this player's perspective
		ArrayList list = new ArrayList();
		for(int i = 0; i < n; i++){
			SOCPlayerTracker pt = (SOCPlayerTracker) rb.getPlayerTrackers()[i];
			pt.recalcLargestArmyETA();pt.recalcLongestRoadETA();pt.recalcWinGameETA(); //for storing the ETAs for the special loading case
			list.add(pt);
		}
		DeepCopy.copyToFile(list, "" + pn, mes.getFolder()); //write the object to file
		//if stac brain type clone both the brainInfo container and the declarative memory
		if(brainType.equals(StacRobotBrain.class.getName()) || brainType.equals(StacRobotBrainRandom.class.getName())
				|| brainType.equals(StacRobotBrainFlatMCTS.class.getName()) || brainType.equals(MCTSRobotBrain.class.getName())){
			StacRobotBrainInfo brainInfo = rb.getInfo();
			DeepCopy.copyToFile(brainInfo, "" + pn, mes.getFolder());
			StacRobotDeclarativeMemory memory = ((StacRobotBrain) rb).getMemory();
			DeepCopy.copyToFile(memory, "" + pn, mes.getFolder());
			if(brainType.equals(MCTSRobotBrain.class.getName())) {
				CatanFactoredBelief belief = ((MCTSRobotBrain) rb).getBelief();
				if(belief != null)
					DeepCopy.copyToFile(belief, "" + pn, mes.getFolder());
			}
		}
		else{
			//else just the brainInfo container
			StacRobotBrainInfo brainInfo = rb.getInfo();
			DeepCopy.copyToFile(brainInfo, "" + pn, mes.getFolder());
		}
		//clone the SOCGame object last so we can check that the saving procedure is finished
		DeepCopy.copyToFile(game, "" + pn, mes.getFolder()); //write the game object to file
	} 
	
	//---MD end of handling methods for Save/Load function

    /**
     * handle the admin ping message
     * @param mes  the message
     */
    protected void handleADMINPING(SOCAdminPing mes)
    {
        D.ebugPrintlnINFO("*** Admin Ping message = " + mes);

        SOCGame ga = games.get(mes.getGame());

        //
        //  if the robot hears a PING and is in the game
        //  where the admin is, then just say "OK".
        //  otherwise, join the game that the admin is in
        //
        //  note: this is a hack because the bot never
        //        leaves the game and the game must be
        //        killed by the admin
        //
        if (ga != null)
        {
            sendText(ga, "OK");
        }
        else
        {
            put(SOCJoinGame.toCmd(nickname, password, SOCMessage.EMPTYSTR, mes.getGame()));
        }
    }

    /**
     * handle the admin reset message
     * @param mes  the message
     */
    protected void handleADMINRESET(SOCAdminReset mes)
    {
        D.ebugPrintlnINFO("*** Admin Reset message = " + mes);
//        disconnectReconnect(); //this shouldn't be executed anyway but just to be safe
    }

    /**
     * handle the update robot params message
     * @param mes  the message
     */
    protected void handleUPDATEROBOTPARAMS(SOCUpdateRobotParams mes)
    {
        currentRobotParameters = new SOCRobotParameters(mes.getRobotParameters());

        if (! printedInitialWelcome)
        {
            // Needed only if server didn't send StatusMessage during initial connect.
            // Server won't send status unless its Debug Mode is on.
            System.err.println("Robot " + getNickname() + ": Authenticated to server.");
            printedInitialWelcome = true;
        }
        if (D.ebugIsEnabled())
            D.ebugPrintlnINFO("*** current robot parameters = " + currentRobotParameters);
    }

    /**
     * handle the "join game request" message.
     * Remember the game options, and record in {@link #seatRequests}.
     * Send a {@link SOCJoinGame JOINGAME} to server in response.
     * Server will reply with {@link SOCJoinGameAuth JOINGAMEAUTH}.
     *<P>
     * Board resets are handled similarly.
     *<P>
     * In v1.x this method was {@code handleJOINGAMEREQUEST}.
     *
     * @param mes  the message
     *
     * @see #handleRESETBOARDAUTH(SOCResetBoardAuth)
     */
    protected void handleBOTJOINGAMEREQUEST(SOCBotJoinGameRequest mes)
    {
        D.ebugPrintlnINFO("**** handleBOTJOINGAMEREQUEST ****");

        final String gaName = mes.getGame();

        if ((testQuitAtJoinreqPercent != 0) && (new Random().nextInt(100) < testQuitAtJoinreqPercent))
        {
            System.err.println
                (" -- " + nickname + " leaving at JoinGameRequest('" + gaName + "', " + mes.getPlayerNumber()
                 + "): " + PROP_JSETTLERS_BOTS_TEST_QUIT_AT_JOINREQ);
            put(new SOCLeaveAll().toCmd());

            try { Thread.sleep(200); } catch (InterruptedException e) {}  // wait for send/receive
            disconnect();
            return;  // <--- Disconnected from server ---
        }

        final Map<String,SOCGameOption> gaOpts = mes.getOptions(knownOpts);
        if (gaOpts != null)
            gameOptions.put(gaName, new SOCGameOptionSet(gaOpts));

        seatRequests.put(gaName, Integer.valueOf(mes.getPlayerNumber()));
        if (put(SOCJoinGame.toCmd(nickname, password, SOCMessage.EMPTYSTR, gaName)))
        {
            D.ebugPrintlnINFO("**** sent SOCJoinGame ****");
        }
    }

    /**
     * handle the "status message" message by printing it to System.err;
     * messages with status value 0 are ignored (no problem is being reported)
     * once the initial welcome message has been printed.
     * Status {@link SOCStatusMessage#SV_SERVER_SHUTDOWN} calls {@link #disconnect()}
     * so as to not print futile reconnect attempts on the terminal.
     * @param mes  the message
     * @since 1.1.00
     */
    @Override
    protected void handleSTATUSMESSAGE(SOCStatusMessage mes)
    {
        int sv = mes.getStatusValue();
        if (sv == SOCStatusMessage.SV_OK_DEBUG_MODE_ON)
            sv = 0;
        else if (sv == SOCStatusMessage.SV_SERVER_SHUTDOWN)
        {
            disconnect();
            return;
        }

        if ((sv != 0) || ! printedInitialWelcome)
        {
            System.err.println("Robot " + getNickname() + ": Status "
                + sv + " from server: " + mes.getStatus());
            if (sv == 0)
                printedInitialWelcome = true;
        }
    }

    /**
     * handle the "join game authorization" message
     * @param mes  the message
     * @param isPractice Is the server local for practice, or remote?
     * @throws IllegalStateException if board size {@link SOCGameOption} "_BHW" isn't defined (unlikely internal error)
     */
    @Override
    protected void handleJOINGAMEAUTH(SOCJoinGameAuth mes, final boolean isPractice)
    {
        gamesPlayed++;

        final String gaName = mes.getGame();

        final SOCGameOptionSet gameOpts = gameOptions.get(gaName);
        final int bh = mes.getBoardHeight(), bw = mes.getBoardWidth();
        if ((bh != 0) || (bw != 0))
        {
            // Encode board size to pass through game constructor.
            // gameOpts won't be null, because bh, bw are used only with SOCBoardLarge which requires a gameopt
            SOCGameOption opt = knownOpts.getKnownOption("_BHW", true);
            if (opt == null)
                throw new IllegalStateException("Internal error: Game opt _BHW not known");
            opt.setIntValue((bh << 8) | bw);
            gameOpts.put(opt);
        }

        try
        {
            final SOCGame ga = new SOCGame(gaName, gameOpts, knownOpts);
            ga.isPractice = isPractice;
            ga.serverVersion = (isPractice) ? sLocalVersion : sVersion;
            games.put(gaName, ga);

            CappedQueue<SOCMessage> brainQ = new CappedQueue<SOCMessage>();
            brainQs.put(gaName, brainQ);

            SOCRobotBrain rb = createBrain(currentRobotParameters, ga, brainQ);
            robotBrains.put(gaName, rb);

            //pass on the information collected by the previous STAC brains
            if (rb instanceof StacRobotBrain) {
                ((StacRobotBrain) rb).setPersistentBrainInformation(persistentBrainInformation);
            }
        } catch (IllegalArgumentException e) {
            System.err.println
                ("Sync error: Bot " + nickname + " can't join game " + gaName + ": " + e.getMessage());
            brainQs.remove(gaName);
            leaveGame(gaName);
        }
    }

    /**
     * handle the "game members" message, which indicates the entire game state has now been sent.
     * If we have a {@link #seatRequests} for this game, request to sit down now: send {@link SOCSitDown}.
     * @param mes  the message
     */
    @Override
    protected void handleGAMEMEMBERS(SOCGameMembers mes)
    {
        /**
         * sit down to play
         */
        Integer pn = seatRequests.get(mes.getGame());

        try
        {
            //wait(Math.round(Math.random()*1000));
        }
        catch (Exception e)
        {
            ;
        }

        if (pn != null)
        {
            put(SOCSitDown.toCmd(mes.getGame(), SOCMessage.EMPTYSTR, pn.intValue(), true));
        } else {
            System.err.println("** Cannot sit down: Assert failed: null pn for game " + mes.getGame());
        }
    }

    /**
     * handle any per-game message that just needs to go into its game's {@link #brainQs}.
     * This includes all messages that the {@link SOCRobotBrain} needs to react to.
     * @param mes  the message
     * @since 2.0.00
     */
    protected void handlePutBrainQ(SOCMessageForGame mes)
    {
        CappedQueue<SOCMessage> brainQ = brainQs.get(mes.getGame());

        if (brainQ != null)
        {
            try
            {
                brainQ.put((SOCMessage)mes);
            }
            catch (CutoffExceededException exc)
            {
                D.ebugPrintlnINFO("CutoffExceededException" + exc);
            }
        }
    }

    /**
     * Handle the "game text message" message, including
     * debug text messages to the robot which start with
     * the robot's nickname + ":".
     * @param mes  the message
     */
    @Override
    protected void handleGAMETEXTMSG(SOCGameTextMsg mes)
    {
        //D.ebugPrintln(mes.getNickname()+": "+mes.getText());
        if (mes.getText().startsWith(nickname))
        {
            handleGAMETEXTMSG_debug(mes);
        }
    }

    /**
     * Handle the "parse result" message.
     * Forward the string with the parse result to the brain playing the corresponding game.
     * @param mes  the message
     */
    protected void handlePARSERESULT(SOCParseResult mes) {
        CappedQueue brainQ = (CappedQueue) brainQs.get(mes.getGame());

        if (brainQ != null) {
            try {
                brainQ.put(mes);
            }
            catch (CutoffExceededException exc) {
                D.ebugPrintlnINFO("CutoffExceededException" + exc);
            }
        }
    }

    /**
     * Handle debug text messages from players to the robot, which start with
     * the robot's nickname + ":".
     * @since 1.1.12
     */
    protected void handleGAMETEXTMSG_debug(SOCGameTextMsg mes)
    {
        final int nL = nickname.length();
        try
        {
            if (mes.getText().charAt(nL) != ':')
                return;
        } catch (IndexOutOfBoundsException e) {
            return;
        }
        final String gaName = mes.getGame();
        final String dcmd = mes.getText().substring(nL);

        if (dcmd.startsWith(":debug-off"))
        {
            SOCGame ga = games.get(gaName);
            SOCRobotBrain brain = robotBrains.get(gaName);

            if (brain != null)
            {
                brain.turnOffDRecorder();
                sendText(ga, "Debug mode OFF");
            }
        }

        else if (dcmd.startsWith(":debug-on"))
        {
            SOCGame ga = games.get(gaName);
            SOCRobotBrain brain = robotBrains.get(gaName);

            if (brain != null)
            {
                brain.turnOnDRecorder();
                sendText(ga, "Debug mode ON");
            }
        }

        else if (dcmd.startsWith(":current-plans") || dcmd.startsWith(":cp"))
        {
            sendRecordsText(gaName, CURRENT_PLANS, false);
        }

        else if (dcmd.startsWith(":current-resources") || dcmd.startsWith(":cr"))
        {
            sendRecordsText(gaName, CURRENT_RESOURCES, false);
        }

        else if (dcmd.startsWith(":last-plans") || dcmd.startsWith(":lp"))
        {
            sendRecordsText(gaName, CURRENT_PLANS, true);
        }

        else if (dcmd.startsWith(":last-resources") || dcmd.startsWith(":lr"))
        {
            sendRecordsText(gaName, CURRENT_RESOURCES, true);
        }

        else if (dcmd.startsWith(":last-move") || dcmd.startsWith(":lm"))
        {
            SOCRobotBrain brain = robotBrains.get(gaName);

            if ((brain != null) && (brain.getOldDRecorder().isOn()))
            {
                SOCPossiblePiece lastMove = brain.getLastMove();

                if (lastMove != null)
                {
                    String key = null;

                    switch (lastMove.getType())
                    {
                    case SOCPossiblePiece.CARD:
                        key = "DEVCARD";
                        break;

                    case SOCPossiblePiece.ROAD:
                        key = "ROAD" + lastMove.getCoordinates();
                        break;

                    case SOCPossiblePiece.SETTLEMENT:
                        key = "SETTLEMENT" + lastMove.getCoordinates();
                        break;

                    case SOCPossiblePiece.CITY:
                        key = "CITY" + lastMove.getCoordinates();
                        break;

                    case SOCPossiblePiece.SHIP:
                        key = "SHIP" + lastMove.getCoordinates();
                        break;
                    }

                    sendRecordsText(gaName, key, true);
                }
            } else {
                sendText(games.get(gaName), HINT_SEND_DEBUG_ON_FIRST);
            }
        }

        else if (dcmd.startsWith(":consider-move ") || dcmd.startsWith(":cm "))
        {
            String[] tokens = dcmd.split(" ");  // ":consider-move road 154"
            final int L = tokens.length;
            String keytoken = (L > 2) ? tokens[L-2].trim() : "(missing)",
                   lasttoken = (L > 1) ? tokens[L-1].trim() : "(missing)",
                   key = null;

            if (lasttoken.equals("card"))
                key = "DEVCARD";
            else if (keytoken.equals("road"))
                key = "ROAD" + lasttoken;
            else if (keytoken.equals("ship"))
                key = "SHIP" + lasttoken;
            else if (keytoken.equals("settlement"))
                key = "SETTLEMENT" + lasttoken;
            else if (keytoken.equals("city"))
                key = "CITY" + lasttoken;

            final SOCGame ga = games.get(gaName);
            if (key == null)
            {
                sendText(ga, "Unknown :consider-move type: " + keytoken);
                return;
            }

            sendRecordsText(gaName, key, true);
        }

        else if (dcmd.startsWith(":last-target") || dcmd.startsWith(":lt"))
        {
            SOCRobotBrain brain = robotBrains.get(gaName);

            if ((brain != null) && (brain.getDRecorder().isOn()))
            {
                SOCPossiblePiece lastTarget = brain.getLastTarget();

                if (lastTarget != null)
                {
                    String key = null;

                    switch (lastTarget.getType())
                    {
                    case SOCPossiblePiece.CARD:
                        key = "DEVCARD";
                        break;

                    case SOCPossiblePiece.ROAD:
                        key = "ROAD" + lastTarget.getCoordinates();
                        break;

                    case SOCPossiblePiece.SETTLEMENT:
                        key = "SETTLEMENT" + lastTarget.getCoordinates();
                        break;

                    case SOCPossiblePiece.CITY:
                        key = "CITY" + lastTarget.getCoordinates();
                        break;

                    case SOCPossiblePiece.SHIP:
                        key = "SHIP" + lastTarget.getCoordinates();
                        break;
                    }

                    sendRecordsText(gaName, key, false);
                }
            } else {
                sendText(games.get(gaName), HINT_SEND_DEBUG_ON_FIRST);
            }
        }

        else if (dcmd.startsWith(":consider-target ") || dcmd.startsWith(":ct "))
        {
            String[] tokens = dcmd.split(" ");  // ":consider-target road 154"
            final int L = tokens.length;
            String keytoken = (L > 2) ? tokens[L-2].trim() : "(missing)",
                   lasttoken = (L > 1) ? tokens[L-1].trim() : "(missing)",
                   key = null;

            if (lasttoken.equals("card"))
                key = "DEVCARD";
            else if (keytoken.equals("road"))
                key = "ROAD" + lasttoken;
            else if (keytoken.equals("ship"))
                key = "SHIP" + lasttoken;
            else if (keytoken.equals("settlement"))
                key = "SETTLEMENT" + lasttoken;
            else if (keytoken.equals("city"))
                key = "CITY" + lasttoken;

            final SOCGame ga = games.get(gaName);
            if (key == null)
            {
                sendText(ga, "Unknown :consider-target type: " + keytoken);
                return;
            }

            sendRecordsText(gaName, key, false);
        }

        else if (dcmd.startsWith(":print-vars") || dcmd.startsWith(":pv"))
        {
            // "prints" the results as series of SOCGameTextMsg to game
            debugPrintBrainStatus(gaName, true);
        }

        else if (dcmd.startsWith(":stats"))
        {
            SOCGame ga = games.get(gaName);
            sendText(ga, "Games played:" + gamesPlayed);
            sendText(ga, "Games finished:" + gamesFinished);
            sendText(ga, "Games won:" + gamesWon);
            sendText(ga, "Clean brain kills:" + cleanBrainKills);
            sendText(ga, "Brains running: " + robotBrains.size());

            Runtime rt = Runtime.getRuntime();
            sendText(ga, "Total Memory:" + rt.totalMemory());
            sendText(ga, "Free Memory:" + rt.freeMemory());
        }

        else if (dcmd.startsWith(":gc"))
        {
            SOCGame ga = games.get(gaName);
            Runtime rt = Runtime.getRuntime();
            rt.gc();
            sendText(ga, "Free Memory:" + rt.freeMemory());
        }

    }

    /**
     * handle the "someone is sitting down" message
     * @param mes  the message
     */
    @Override
    protected SOCGame handleSITDOWN(SOCSitDown mes)
    {
        final String gaName = mes.getGame();

        /**
         * tell the game that a player is sitting
         */
        final SOCGame ga = super.handleSITDOWN(mes);
        if (ga == null)
            return null;

        /**
         * let the robot brain find our player object if we sat down
         */
        final int pn = mes.getPlayerNumber();
        if (nickname.equals(mes.getNickname()))
        {
            SOCRobotBrain brain = robotBrains.get(gaName);

            if (brain.ourPlayerData != null)
            {
                if ((pn == brain.ourPlayerNumber) && nickname.equals(ga.getPlayer(pn).getName()))
                    return ga;  // already sitting in this game at this position, OK (can happen during loadgame)

                throw new IllegalStateException
                    ("bot " + nickname + " game " + gaName
                     + ": got sitdown(pn=" + pn + "), but already sitting at pn=" + brain.ourPlayerNumber);
            }

            /**
             * retrieve the proper face for our strategy
             */
            int faceId;
            // TODO: Move this logic into the brain itself
            switch (brain.getRobotParameters().getStrategyType())
            {
            case SOCRobotDMImpl.SMART_STRATEGY:
                faceId = -1;  // smarter robot face
                break;

            default:
                faceId = 0;   // default robot face
            }

            if(brain.getClass() == StacRobotBrain.class){
                if(((StacRobotBrain)brain).isRobotType(StacRobotType.PLAYER_ICON)){
                    faceId = (int) ((StacRobotBrain)brain).getTypeParam(StacRobotType.PLAYER_ICON);
                    if(faceId > 73 || faceId < -1){
                        faceId = -1;//smarter face in case the wrong param was used
                    }
                }else{
                    Random r = new Random();
                    faceId = r.nextInt(74) - 1;
                }
            }

            brain.setOurPlayerData();
            brain.start();
            Server.trackThread(brain, this);

            /**
             * change our face to the robot face
             */
            put(new SOCChangeFace(ga.getName(), pn, faceId).toCmd());
        }
        else
        {
            /**
             * add tracker for player in previously vacant seat
             */
            SOCRobotBrain brain = robotBrains.get(gaName);

            if (brain != null)
                brain.addPlayerTracker(pn);
        }

        return ga;
    }

    /**
     * handle the general "start game" message
     * @param mes  the message
     * @see #handleStacSTARTGAME(StacStartGame)
     */
    protected void handleSTARTGAME(SOCStartGame mes) {
    	StacRobotBrain.setChatNegotiation(false);//this is set here as the game may be played over the network and not locally
    	SOCRobotBrain brain = (SOCRobotBrain) robotBrains.get(mes.getGame());
        brain.startGameChat();

    	if(brain.getClass().getName().equals(MCTSRobotBrain.class.getName())){
    		((MCTSRobotBrain)brain).generateBoard();
    	}

    	if(brain.getClass().getName().equals(OriginalSSRobotBrain.class.getName())){
    		((OriginalSSRobotBrain)brain).sendGameToSmartSettlers();
    	}

        handlePutBrainQ((SOCMessageForGame) mes);  // added for v2.x when gameState became a field of this message
    }

    /**
     * handle the STAC "start game" message
     * @param mes  the message
     * @see #handleSTARTGAME(SOCStartGame)
     * @since 2.4.50
     */
    protected void handleSTACSTARTGAME(StacStartGame mes) {
    	StacRobotBrain.setChatNegotiation(mes.getChatNegotiationsFlag());//this is set here as the game may be played over the network and not locally
    	SOCRobotBrain brain = (SOCRobotBrain) robotBrains.get(mes.getGame());
    	if(mes.getLoadFlag())
    		handleLOADGAME(new SOCLoadGame(mes.getGame(),mes.getFolder()));
    	else
    		brain.startGameChat();
    	
    	if(brain.getClass().getName().equals(MCTSRobotBrain.class.getName())){
    		((MCTSRobotBrain)brain).generateBoard();
    	}
    	
    	if(brain.getClass().getName().equals(OriginalSSRobotBrain.class.getName())){
    		((OriginalSSRobotBrain)brain).sendGameToSmartSettlers();
    	}

        handlePutBrainQ((SOCMessageForGame) mes);  // gameState may be a field of this message
    }

    /**
     * handle the "delete game" message
     * @param mes  the message
     */
    @Override
    protected void handleDELETEGAME(SOCDeleteGame mes)
    {
        SOCRobotBrain brain = robotBrains.get(mes.getGame());

        if (brain != null)
        {
            SOCGame ga = games.get(mes.getGame());

            if (ga != null)
            {
                if (ga.getGameState() == SOCGame.OVER)
                {
                    gamesFinished++;

                    if (ga.getPlayer(nickname).getTotalVP() >= ga.getVpWinner())
                    {
                        gamesWon++;
                        // TODO: should check actual winning player number (getCurrentPlayerNumber?)
                    }
                }

                brain.kill();
                robotBrains.remove(mes.getGame());
                brainQs.remove(mes.getGame());
                games.remove(mes.getGame());
            }
        }
    }

    /**
     * Handle the "game state" message; instead of immediately updating state,
     * calls {@link #handlePutBrainQ(SOCMessageForGame)}.
     * @param mes  the message
     */
    @Override
    protected void handleGAMESTATE(SOCGameState mes)
    {
        SOCGame ga = games.get(mes.getGame());

        if (ga != null)
        {
            handlePutBrainQ(mes);
        }

    }

    /**
     * handle the "put piece" message
     * @param mes  the message
     */
    protected void handlePUTPIECE(SOCPutPiece mes)
    {
        CappedQueue<SOCMessage> brainQ = brainQs.get(mes.getGame());

        if (brainQ != null)
        {
            try
            {
                brainQ.put(mes);
            }
            catch (CutoffExceededException exc)
            {
                D.ebugPrintlnINFO("CutoffExceededException" + exc);
            }

            SOCGame ga = games.get(mes.getGame());

            if (ga != null)
            {
                // SOCPlayer pl = ga.getPlayer(mes.getPlayerNumber());
                // JDM TODO - Was this in stock client?
            }
        }
    }

    /**
     * handle the "dismiss robot" message
     * @param mes  the message
     */
    protected void handleROBOTDISMISS(SOCRobotDismiss mes)
    {
        //SOCGame ga = games.get(mes.getGame());
        //CappedQueue<SOCMessage> brainQ = brainQs.get(mes.getGame());
        String gaName = mes.getGame();
        leaveGame(gaName, "Dismissed", false);
    }

    protected void handleGenericMessage(SOCMessageForGame mes)
    {
        CappedQueue brainQ = (CappedQueue) brainQs.get(mes.getGame());

        if (brainQ != null)
        {
            try
            {
                brainQ.put(mes);
            }
            catch (CutoffExceededException exc)
            {
                D.ebugPrintlnINFO("CutoffExceededException" + exc);
            }
        }
    }

    /**
     * handle board reset
     * (new game with same players, same game name).
     * Destroy old Game object.
     * Unlike <tt>SOCDisplaylessPlayerClient.handleRESETBOARDAUTH</tt>, don't call {@link SOCGame#resetAsCopy()}.
     *<P>
     * Take robotbrain out of old game, don't yet put it in new game.
     * Let server know we've done so, by sending LEAVEGAME via {@link #leaveGame(SOCGame, String, boolean, boolean)}.
     * Server will soon send a BOTJOINGAMEREQUEST if we should join the new game.
     *
     * @param mes  the message
     *
     * @see soc.server.SOCServer#resetBoardAndNotify(String, int)
     * @see #handleBOTJOINGAMEREQUEST(SOCBotJoinGameRequest)
     * @since 1.1.00
     */
    @Override
    protected void handleRESETBOARDAUTH(SOCResetBoardAuth mes)
    {
        D.ebugPrintlnINFO("**** handleRESETBOARDAUTH ****");

        String gname = mes.getGame();
        SOCGame ga = games.get(gname);
        if (ga == null)
            return;  // Not one of our games

        SOCRobotBrain brain = robotBrains.get(gname);
        if (brain != null)
            brain.kill();
        leaveGame(ga, "resetboardauth", false, false);  // Same as in handleROBOTDISMISS
        ga.destroyGame();
    }

    @Override
    public void putPiece(SOCGame ga, SOCPlayingPiece pp)
    {
        super.putPiece(ga, pp);

        SOCRobotBrain brain = (SOCRobotBrain) robotBrains.get(ga.getName());
        if (brain.getClass().getName().equals(StacRobotBrain.class.getName())) {
            ((StacRobotBrain)brain).resetNumberOfOffersWithoutBuildingAction();
        }
        if (pp.getType() == SOCPlayingPiece.SETTLEMENT)
        {
        	
        	if(brain.getClass().getName().equals(OriginalSSRobotBrain.class.getName())){
        		((OriginalSSRobotBrain)brain).lastSettlement = pp.getCoordinates();
        	}
        }
    }

    /**
     * Call sendText on each string element of a record
     * from {@link SOCRobotBrain#getDRecorder()} or {@link SOCRobotBrain#getOldDRecorder() .getOldDRecorder()}.
     * If no records found or ! {@link DebugRecorder#isOn()}, sends text to let the user know.
     *
     * @param gaName  Game name; if no brain found for game, does nothing
     * @param key  Recorder key for strings to send; not {@code null}
     * @param oldNotCurrent  True if should use {@link SOCRobotBrain#getOldDRecorder()
     *     instead of {@link SOCRobotBrain#getDRecorder() .getDRecorder()}
     * @since 1.1.00
     */
    protected void sendRecordsText
        (final String gaName, final String key, final boolean oldNotCurrent)
    {
        final SOCRobotBrain brain = robotBrains.get(gaName);
        if (brain == null)
            return;

        final SOCGame ga = games.get(gaName);

        final DebugRecorder recorder = (oldNotCurrent) ? brain.getOldDRecorder(): brain.getDRecorder();
        if (! recorder.isOn())
        {
            sendText(ga, HINT_SEND_DEBUG_ON_FIRST);
            return;
        }

        final List<String> record = recorder.getRecord(key);

        if (record != null)
            for (String str : record)
                sendText(ga, str);
        else
            sendText(ga, "No debug records for " + key);
    }

    /**
     * Print brain variables and status for this game, to {@link System#err}
     * or as {@link SOCGameTextMsg} sent to the game's members,
     * by calling {@link SOCRobotBrain#debugPrintBrainStatus()}.
     * @param gameName  Game name; if no brain for that game, do nothing.
     * @param sendTextToGame  Send to game as {@link SOCGameTextMsg} if true,
     *     otherwise print to {@link System#err}.
     * @since 1.1.13
     */
    public void debugPrintBrainStatus(String gameName, final boolean sendTextToGame)
    {
        SOCRobotBrain brain = robotBrains.get(gameName);
        if (brain == null)
            return;

        List<String> rbSta = brain.debugPrintBrainStatus();
        if (sendTextToGame)
            for (final String st : rbSta)
                put(new SOCGameTextMsg(gameName, nickname, st).toCmd());
        else
            for (final String st : rbSta)
                System.err.println(st);
    }

    /**
     * the user leaves the given game
     *
     * @param ga   the game
     * @param leaveReason reason for leaving
     * @param showReason  If true print bot, game, and {@code leaveReason} even if not {@link D#ebugIsEnabled()}
     * @param showDebugTrace  If true print current thread's stack trace
     */
    public void leaveGame
        (final SOCGame ga, final String leaveReason, final boolean showReason, final boolean showDebugTrace)
    {
        if (ga != null)
        {
            leaveGame(ga.getName(), leaveReason, showDebugTrace);
        }
    }
    
    protected void leaveGame(String gaName, String leaveReason, boolean showDebugTrace)
    {
        final boolean showReason = true;
        SOCRobotBrain brain = robotBrains.get(gaName);

        //store the information we want to pass on to the next robot brain for the next game
        if (brain instanceof StacRobotBrain) {
            persistentBrainInformation = ((StacRobotBrain) brain).getPersistentBrainInformation();
            //((StacRobotBrain) brain).showLearningResults();
        }

        brain.kill();    
        robotBrains.remove(gaName);
        games.remove(gaName);
        gameOptions.remove(gaName);
        brainQs.remove(gaName);
        seatRequests.remove(gaName);

        final String r = (showReason || D.ebugIsEnabled())
            ? ("L1833 robot " + nickname + " leaving game " + gaName + " due to " + leaveReason)
            : null;
        if (showReason)
            soc.debug.D.ebugPrintlnINFO(r);
        else if (r != null)
            D.ebugPrintlnINFO(r);

        if (showDebugTrace)
        {
            soc.debug.D.ebugFATAL(null, "Leaving game here");
            System.err.flush();
        }

        put(SOCLeaveGame.toCmd(nickname, "-", gaName));
    }

    /**
     * add one to the number of clean brain kills
     */
    public void addCleanKill()
    {
        cleanBrainKills++;
    }

    /**
     * Connection to server has raised an error; leave all games, then try to reconnect.
     */
    @Override
    public void destroy()
    {
        SOCLeaveAll leaveAllMes = new SOCLeaveAll();
        put(leaveAllMes.toCmd());
        //disconnectReconnect(); do we ever need to reconnect? only running locally for now (NOTE: uncomment if this changes, but it will cause out of memory issues)
        //if (ex != null)
            // Comment out: this happen whenever the server is shut down.  Not a problem for local tests.
        //    System.err.println("Reconnect to server failed: " + ex);
    }

    public void kill(){
    	super.kill();
    	Server.killAllThreadsCreatedBy(this);//also kill all threads started by this thread
    	SOCServer.removeClient(nickname);
    }
    
    // get and set methods for HWU negotiators
    
    public void setSupervisedNegotiator( BayesianSupervisedLearner sltrader ) {
    	this.sltrader = sltrader;
    }
    
    public BayesianSupervisedLearner getSupervisedNegotiator() {
    	return sltrader;
    }
    
	public void setDeepNegotiator(SimpleAgent deeptrader) {
		this.deeptrader = deeptrader;
	}

	public SimpleAgent getDeepNegotiator() {
		return this.deeptrader;
	}

    public void setMDPNegotiator( LearningNegotiator mdp_negotiator ) {
    	this.mdp_negotiator = mdp_negotiator;
    }
    
    public LearningNegotiator getMDPNegotiator() {
    	return mdp_negotiator;
    }

    /**
     * for stand-alones
     */
    public static void main(String[] args)
    {
        if (args.length < 5)
        {
            System.err.println("Java Settlers robotclient " + Version.version() +
                    ", build " + Version.buildnum());
            System.err.println("usage: java soc.robot.SOCRobotClient host port_number bot_nickname password cookie");
            return;
        }

        SOCRobotClient ex1 = new SOCRobotClient
            (new SOCDefaultRobotFactory(), new ServerConnectInfo(args[0], Integer.parseInt(args[1]), args[4]), args[2], args[3]);
        ex1.init();
    }

}
