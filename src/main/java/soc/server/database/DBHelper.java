package soc.server.database;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Properties;
import soc.dialogue.StacTradeMessage;
import soc.game.SOCGame;

import soc.game.SOCPlayer;
import soc.game.SOCResourceSet;
import soc.message.SOCAcceptOffer;
import soc.message.SOCBankTrade;
import soc.message.SOCMakeOffer;
import soc.message.SOCRejectOffer;
import soc.robot.stac.Persuasion;
import soc.util.SOCRobotParameters;

/**
 * Interface for DB helper class.  This will allow us to use either an actual DB or a simple logger, in the 
 *  event we are running simulations and don't care about user authentication, etc.
 * @author kho30
 *
 */
public interface DBHelper {
    
    public int getNumGamesDone();

    public void initialize(String user, String pswd, Properties props)
        throws IllegalArgumentException, DBSettingMismatchException, SQLException, IOException;

    /**
     * Were we able to {@link #initialize(String, String, Properties)}
     * and connect to the database?
     * True if db is connected and available; false if never initialized,
     * or if {@link #cleanup(boolean)} was called.
     *
     * @return  True if database available
     */
    public boolean isInitialized();

    /**
     * Get the detected schema version of the currently connected database.
     * To upgrade an older schema to the latest available, see {@link #upgradeSchema(Set)}.
     * @return Schema version, such as {@link SOCDBHelper#SCHEMA_VERSION_ORIGINAL} or {@link SOCDBHelper#SCHEMA_VERSION_1200}
     * @see SOCDBHelper#SCHEMA_VERSION_LATEST
     * @see #isSchemaLatestVersion()
     * @see #isPasswordLengthOK(String)
     */
    public int getSchemaVersion();

    /**
     * Search for and return this user (nickname) if it exists in the database.
     * If schema &gt;= {@link SOCDBHelper#SCHEMA_VERSION_1200}, this check is case-insensitive.
     * Returns their nickname as stored in the database.
     *
     * @param userName  User nickname to check
     * @return  Nickname if found in users table, {@code null} otherwise or if no database is currently connected
     * @throws IllegalArgumentException if {@code userName} is {@code null}
     * @throws SQLException if any unexpected database problem
     * @since 1.2.00
     * @see #authenticateUserPassword(String, String, AuthPasswordRunnable)
     */
    public String getUser(String userName)
        throws IllegalArgumentException, SQLException;

    /**
     * Check if this user exists, if so validate their password from the database.
     * If schema &gt;= {@link SOCDBHelper#SCHEMA_VERSION_1200}, username check is case-insensitive.
     * For use of the originally-cased name from that search, if successful this method
     * returns their nickname as stored in the database.
     *<P>
     * For running without the optional database, or when user accounts are optional:
     * If never connected to a database or user's nickname isn't in the users table,
     * and {@code sPassword} is "", returns {@code sUserName}.
     *<P>
     * This method replaces {@code getUserPassword(..)} used before v1.2.00.
     *
     * @param sUserName Username needing password authentication
     * @param sPassword  Password being tried, or "" if none.
     *     Different password schemes have different maximum password lengths;
     *     see {@link #isPasswordLengthOK(String)}.
     *     Passwords longer than 256 characters are always rejected here before checking {@code PW_SCHEME}.
     *     If this user has {@link SOCDBHelper#PW_SCHEME_NONE}, for backwards compatibility {@code sPassword} is
     *     truncated to 20 characters ({@link SOCDBHelper#PW_MAX_LEN_SCHEME_NONE}).
     * @param authCallback  Optional callback to make after authentication lookups and hashing succeed or fail.
     *     This is useful because {@link BCrypt} password hashing is slow by design, and so is done in another
     *     thread.  If not {@code null}, {@code authCallback} is called at the end of this method,
     *     either in the caller's thread or in a thread dedicated to {@code BCrypt} calls.
     * @return user's nickname if password is correct;
     *     {@code sUserName} if password is "" but user doesn't exist in db
     *     or if database is not currently connected;
     *     {@code null} if account exists in db and password is wrong.
     * @throws SQLException if any unexpected database problem.
     *     <P>
     *     Only the {@code BCrypt} call will be done in a separate thread; all DB activity happens in this method
     *     in the caller's thread, so SQLExceptions will be thrown to the caller and not lost or ignored.
     * @see #updateUserPassword(String, String)
     * @see #getUser(String)
     * @since 1.2.00
     */
    public String authenticateUserPassword
        (final String sUserName, String sPassword, final AuthPasswordRunnable authCallback)
        throws SQLException;	    

    /**
     * Update a user's password if the user is in the database.
     * If schema &gt;= {@link SOCDBHelper#SCHEMA_VERSION_1200}, the password will be encoded with {@link SOCDBHelper#PW_SCHEME_BCRYPT}.
     * @param userName  Username to update.  Does not validate this user exists: Call {@link #getUser(String)}
     *     first to do so.  If schema &gt;= {@link SOCDBHelper#SCHEMA_VERSION_1200}, {@code userName} is case-insensitive.
     * @param newPassword  New password. Can't be null or "", and must pass
     *     {@link #isPasswordLengthOK(String)} which depends on {@link #getSchemaVersion()}.
     * @return  True if the update command succeeded, false if can't connect to db.
     *     <BR><B>Note:</B> If there is no user with {@code userName}, will nonetheless return true.
     * @throws IllegalArgumentException  If user or password are null, or password is too short or too long
     * @throws SQLException if an error occurs
     * @see #authenticateUserPassword(String, String, AuthPasswordRunnable)
     * @since 1.1.20
     */
    public boolean updateUserPassword(String userName, final String newPassword)
        throws IllegalArgumentException, SQLException;

    /**
     * Get the maximum password length, given the current schema version's encoding scheme
     * ({@link SOCDBHelper#PW_SCHEME_BCRYPT} or {@link SOCDBHelper#PW_SCHEME_NONE}).
     * To check a specific password's length, call {@link #isPasswordLengthOK(String)} instead.
     * @return  Maximum allowed password length for current password scheme
     *     ({@link SOCDBHelper#PW_MAX_LEN_SCHEME_BCRYPT} or {@link SOCDBHelper#PW_MAX_LEN_SCHEME_NONE})
     * @since 1.2.00
     */
    public int getMaxPasswordLength();

    public boolean getTradeReminderSeen(String sUserName) throws SQLException;

    public boolean getUserMustDoTrainingGame(String sUserName) throws SQLException;

    public String getUserFromHost(String host) throws SQLException;

    public boolean createAccount(String userName, String host, String password, String email, long time) throws SQLException;

    public boolean recordLogin(String userName, String host, long time) throws SQLException;

    public boolean updateLastlogin(String userName, long time) throws SQLException;

    public boolean updateShowTradeReminder(String userName, boolean flag) throws SQLException;

    public boolean updateUserMustDoTrainingGame(String userName, boolean flag) throws SQLException;

//	    public boolean saveGameScores(String gameName, String player1, String player2, String player3, String player4, short score1, short score2, short score3, short score4, java.util.Date startTime, boolean regularFlag) throws SQLException;
    public boolean saveGameScores(SOCGame ga, int gameLengthSeconds, boolean winLossOnly) throws SQLException;

    public SOCRobotParameters retrieveRobotParams(String robotName) throws SQLException;

    /**
     * Count the number of users, if any, currently in the users table.
     * @return User count, or -1 if not connected.
     * @throws SQLException if unexpected problem counting the users
     * @since 1.1.19
     */
    public int countUsers()
        throws SQLException;

    /**
     * Close out and shut down the database connection.
     * Any {@link SQLException}s while doing so are caught here.
     * @param isForShutdown  If true, set <tt>connection = null</tt>
     *          so we won't try to reconnect later.
     */
    public void cleanup(boolean isForShutdown);

    // TODO: Augment this with some additional information (eg the resources currently held).  Note
    //  some of this depends on opponent resource tracking - it would be ideal to know what an offerer
    //  thinks his opponent has, but this would need to be logged by the brain/negotiator
    public void logChatTradeOffer(SOCPlayer p, StacTradeMessage tm, int turn, boolean isInitial);
    public void logChatTradeOffer(SOCPlayer p, StacTradeMessage tm, int turn, boolean isInitial, Persuasion persuasionMove, int roundNo);
    public void logTradeEvent(SOCPlayer p, SOCMakeOffer offer, int turn, boolean isInitial, boolean isForced);
    public void logTradeEvent(SOCPlayer accepter, SOCPlayer offerer, SOCAcceptOffer accept, int turn, boolean isForced);
    public void logTradeEvent(SOCPlayer p, SOCMakeOffer offer, int turn, boolean isInitial, Persuasion persuasionMove, int roundNo);
    public void logTradeEvent(SOCPlayer accepter, SOCPlayer offerer, SOCAcceptOffer accept, int turn, Persuasion persuasionMove);
    public void logTradeEvent(SOCPlayer p, SOCRejectOffer reject, int turn);

    public void logBankTradeEvent(SOCPlayer player, SOCBankTrade mes);

    public void newTurn(String gameName, String playerName);

    // Log the result of a player's build plan prediction
    public void logBPPrediction(SOCPlayer p, boolean nullEquiv, boolean correctType, boolean fullEquality);

    // Log the result of a player's has-resources prediction
    public void logHasResourcesPrediction(SOCPlayer p, boolean beliefCorrect, boolean observedCorrect, boolean subsetCorrect, boolean afterRollOfSeven);

    // Log a player's build plan
    public void logBuildPlan(String player, int bpType);

    // Log that a player has build/bought something
    public void logBuildAction(String player, int piece);

    // Log that the player holding a badge (LR/LA) has changed
    public void logLA_LRPlayerChanged(String player, String badge);

    /** Log that a player has received resources */
    public void logResourcesReceivedByTrading(String player, SOCResourceSet resources);// int clay, int ore, int sheep, int wheat, int wood);

    public int EMBARGO_PROPOSE = 0;
    public int EMBARGO_COMPLY = 1;
    public int EMBARGO_LIFT = 2;

    /** Log a change in an embargo for the specified player */
    public void logEmbargoAction(String player, int action);
    
    public int BLOCK = 0;
    public int BLOCK_COMPLY = 1;
    
    /** Log an action related to blocking trades for the specified player */
    public void logBlockingAction(String player, int action);

    /**
     * Interface for callbacks from {@link SOCDBHelper#authenticateUserPassword(String, String, AuthPasswordRunnable)}.
     * See {@link #authResult(String, boolean)} for callback details.
     *
     * @author Jeremy D Monin &lt;jeremy@nand.net&gt;
     * @since 1.2.00
     */
    public static interface AuthPasswordRunnable
    {
        /**
         * Called after user and password are authenticated or rejected, which may be a slow process which runs in
         * its own Thread. So, this callback will occur in the caller's Thread or in a Thread dedicated to
         * {@link BCrypt} calls.
         * @param dbUserName  Username if auth was successful, or {@code null}; same meaning as the String
         *     returned from {@link SOCDBHelper#authenticateUserPassword(String, String, AuthPasswordRunnable)}.
         * @param hadDelay  If true, this callback has been delayed by {@code BCrypt} calculations;
         *     otherwise it's an immediate callback (user not found, password didn't use BCrypt hashing)
         *     and for consistency you might want to delay replying to the client.
         */
        public void authResult(final String dbUserName, final boolean hadDelay);
    }

}
