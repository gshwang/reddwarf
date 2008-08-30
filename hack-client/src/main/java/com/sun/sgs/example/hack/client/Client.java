/*
 * This work is hereby released into the Public Domain. 
 * To view a copy of the public domain dedication, visit 
 * http://creativecommons.org/licenses/publicdomain/ or send 
 * a letter to Creative Commons, 171 Second Street, Suite 300, 
 * San Francisco, California, 94105, USA.
 */

package com.sun.sgs.example.hack.client;

import com.sun.sgs.client.ClientChannel;
import com.sun.sgs.client.ClientChannelListener;

import com.sun.sgs.client.simple.SimpleClient;
import com.sun.sgs.client.simple.SimpleClientListener;

import com.sun.sgs.example.hack.client.gui.ChatPanel;
import com.sun.sgs.example.hack.client.gui.CreatorPanel;
import com.sun.sgs.example.hack.client.gui.GamePanel;
import com.sun.sgs.example.hack.client.gui.LobbyPanel;
import com.sun.sgs.example.hack.client.gui.PasswordDialog;

import com.sun.sgs.example.hack.share.Board;
import com.sun.sgs.example.hack.share.BoardSpace;
import com.sun.sgs.example.hack.share.CharacterStats;
import com.sun.sgs.example.hack.share.Commands;
import com.sun.sgs.example.hack.share.Commands.Command;
import com.sun.sgs.example.hack.share.CreatureInfo;
import com.sun.sgs.example.hack.share.CreatureInfo.Creature;
import com.sun.sgs.example.hack.share.CreatureInfo.CreatureType;
import com.sun.sgs.example.hack.share.GameMembershipDetail;
import com.sun.sgs.example.hack.share.ItemInfo;
import com.sun.sgs.example.hack.share.ItemInfo.Item;
import com.sun.sgs.example.hack.share.ItemInfo.ItemType;
import com.sun.sgs.example.hack.share.SimpleCreature;
import com.sun.sgs.example.hack.share.SimpleItem;
import com.sun.sgs.example.hack.share.RoomInfo;
import com.sun.sgs.example.hack.share.RoomInfo.FloorType;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Container;
import java.awt.Image;
import java.awt.Window;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;

import java.math.BigInteger;

import java.net.PasswordAuthentication;

import java.nio.ByteBuffer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

import javax.swing.JFrame;
import javax.swing.JPanel;


/**
 * This is the main class for the client app. It creates the connection
 * with the server, sets up the GUI elements, and listens for the major
 * events from the server game app.
 */
public class Client extends JFrame implements SimpleClientListener {

    private static final long serialVersionUID = 1;

    private static final Logger logger = 
	Logger.getLogger(Client.class.getName());

    // the simple client connection
    private SimpleClient client;

    // the gui and message handlers for interaction with the lobby
    private LobbyManager lobbyManager;
    private LobbyPanel lobbyPanel;
    private LobbyChannelListener lobbyListener;

    // the gui and message handlers for interacting with character creation
    private CreatorManager creatorManager;
    private CreatorPanel creatorPanel;
    private CreatorChannelListener creatorListener;

    // the gui and messages handlers for chatting
    private ChatManager chatManager;
    private ChatPanel chatPanel;

    // the gui and message handlers for interaction with a dungeon
    private GameManager dungeonManager;
    private GamePanel gamePanel;
    private DungeonChannelListener dungeonListener;

    // the card layout manager for swapping between different panels
    private CardLayout managerLayout;
    private JPanel managerPanel;
    private PasswordDialog pd;
    /**
     * Creates an instance of <code>Client</code>. This sets up the GUI
     * elements and the state for talking with the game server, but does not
     * establish a connection.
     *
     * @throws Exception if there is any problem with the initial setup
     */
    public Client() throws Exception {
        super("Hack 0.5");

        // listen for events on the root window
        addWindowListener(new BasicWindowMonitor());

        // create the managers
        lobbyManager = new LobbyManager();
        creatorManager = new CreatorManager();
        chatManager = new ChatManager();
        dungeonManager = new GameManager();
	
        // setup the listeners to handle communication from the game app
        lobbyListener = new LobbyChannelListener(lobbyManager, chatManager);
        creatorListener = new CreatorChannelListener(creatorManager, 
						     chatManager);
        dungeonListener = new DungeonChannelListener(dungeonManager, 
						     chatManager, 
						     dungeonManager);

        Container c = getContentPane();
        c.setLayout(new BorderLayout());

        // create the GUI panels used for the game elements
        lobbyPanel = new LobbyPanel(lobbyManager);
        creatorPanel = new CreatorPanel(creatorManager);
        gamePanel = new GamePanel(dungeonManager);
        chatPanel = new ChatPanel(chatManager, gamePanel);

        // setup a CardLayout for the game and lobby panels, so we can
        // easily switch between them
        managerLayout = new CardLayout();
        managerPanel = new JPanel(managerLayout);
        managerPanel.add(new JPanel(), "blank");
        managerPanel.add(lobbyPanel, "lobby");
        managerPanel.add(creatorPanel, "creator");
        managerPanel.add(gamePanel, "game");

        c.add(managerPanel, BorderLayout.CENTER);
        c.add(chatPanel, BorderLayout.SOUTH);
    }

    /**
     * Tries to connect to the game server.
     *
     * @throws Exception if the connection fails
     */
    public void connect() throws Exception {
	pd = new PasswordDialog(this, "Name", "Password") {

		@Override
		    public void connect(String login, char[] pass) {
		    try {
			setupClient();
			client.login(System.getProperties());
		    } catch (IOException ex) {
			throw new RuntimeException(ex);
		    }
		}
	    };
	pd.pack();
	pd.setVisible(true);

	if (pd.isCancel()) {
	    setVisible(false);
	    dispose();
	}
    }

    private void setupClient() {
        // setup the client connection
        client = new SimpleClient(this);
        lobbyManager.setClient(client);
        creatorManager.setClient(client);
        dungeonManager.setClient(client);
    }
    
    public PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication(pd.getLogin(), pd.getPassword());
    }

    public void loggedIn() {
	pd.setVisible(false);
	pd.dispose();
	pd = null;
    }

    public void loginFailed(String reason) {
	pd.setConnectionFailed(reason);
    }

    public void disconnected(boolean graceful, String reason) {
	pd.setConnectionFailed(reason);
    }
    public void reconnecting() {}
    public void reconnected() {}


    /**
     * Called when the client joins a communication channel. In this game
     * the client is never on more than one channel at a time, so this
     * is used to switch between states.
     *
     * @param channel the channel that we joined
     */
    public ClientChannelListener joinedChannel(ClientChannel channel) {

        // clear the chat area each time we join a new area
        chatPanel.clearMessages();

        // update the chat manager with the channel, so it knows where to
        // broadcast chat messages
        chatManager.setChannel(channel);

        // see which type of game we've joined, and based on this display
        // the right panel and set the appropriate listener to handle
        // messages from the server
        if (channel.getName().equals("game:lobby")) {
            // we joined the lobby
            // lobbyPanel.clearList();
            logger.fine("joined lobby channel");
            managerLayout.show(managerPanel, "lobby");
            return lobbyListener;
        } 
	else if (channel.getName().equals("game:creator")) {
            // we joined the creator
            logger.fine("joined creator channel");
            managerLayout.show(managerPanel, "creator");
            return creatorListener;
        } 
	else if (channel.getName().startsWith("level:")) {
	    // we are already in a dungeon but must have moved levels,
	    // so return the dungeon listener which is going to handle
	    // all the updates
	    logger.fine("joined level channel: " + channel.getName());
	    return dungeonListener;
	}
	else {
            // we joined some dungeon
            gamePanel.showLoadingScreen();
	    logger.fine("joined dungeon channel:" + channel.getName());
            managerLayout.show(managerPanel, "game");
            // request focus so all key presses are captured
            gamePanel.requestFocusInWindow();

            return dungeonListener;
        }
    }

    public void receivedMessage(ByteBuffer message) {


	if (chatPanel.getSessionId() == null) {
	    byte[] bytes = new byte[message.remaining()];
	    message.get(bytes);
	    BigInteger sessionId = new BigInteger(1, bytes);
	    chatPanel.setSessionId(sessionId);
	}
	else {
	    try {
		int encodedCmd = (int)(message.getInt());
		Command cmd = Commands.decode(encodedCmd);
		switch(cmd) {

		/*
		 * When entering a new game state, the server will send us
		 * a bulk mapping of all the player-ids to their names.
		*/
		case ADD_BULK_PLAYER_IDS:

		    int numIds = message.getInt();

		    Map<BigInteger,String> playerIdsToNames = 
			new HashMap<BigInteger,String>();

		    for (int id = 0; id < numIds; ++id) {
			System.out.println("id: " + id);
			int idLength = message.getInt();
			byte[] bytes = new byte[idLength];
			for (int i = 0; i < idLength; ++i) 
			    bytes[i] = message.get();
			BigInteger playerId = new BigInteger(bytes);
	
			int nameLength = message.getInt();
			char[] arr = new char[nameLength];
			for (int i = 0; i < nameLength; ++i) 
			    arr[i] = message.getChar();
			String playerName = new String(arr);

			playerIdsToNames.put(playerId, playerName);
		    }
 		    
		    chatPanel.addPlayerIdMappings(playerIdsToNames);
		    break;
	    
		/*
		 * When creating a new character, the server will send us
		 * new stats for the character.
		 */
		case NEW_CHARACTER_STATS:
		    //Object[] idAndStats = (Object[])(getObject(message));
		    //Integer  = (Integer)(idAndStats[0]);
		    int encodedCharacterClass = message.getInt();
		    CreatureType characterClassType = 
			CreatureInfo.decodeCreatureType(encodedCharacterClass);
		    CharacterStats stats = decodeStats(message);
		    creatorManager.changeStatistics(characterClassType, stats);
		    break;

		/*
		 * When we join the Lobby, the server will send us a
		 * message of all the available games
		 */
		case UPDATE_AVAILABLE_GAMES: 

		    int numGames = message.getInt();
		    		
		    Collection<GameMembershipDetail> details = 
			new ArrayList<GameMembershipDetail>(numGames);
    
		    for (int i = 0; i < numGames; ++i) {
			int gameNameLen = message.getInt();
			char[] arr = new char[gameNameLen];
			for (int j = 0; j < gameNameLen; ++j) {
			    arr[j] = message.getChar();
			}
			String gameName = new String(arr);
			int playerCount = message.getInt();
			
			details.add(new GameMembershipDetail(gameName, playerCount));
		    }

		    for (GameMembershipDetail detail : details) {
			// for each update, see if it's about the lobby
			// or some specific dungeon
			if (! detail.getGame().equals("game:lobby")) {
			    // it's a specific dungeon, so add the game and
			    // set the initial count
			    lobbyManager.gameAdded(detail.getGame());
			    lobbyManager.playerCountUpdated(detail.getGame(),
							detail.getCount());
			} else {
			    // it's the lobby, so update the count
			    lobbyManager.playerCountUpdated(detail.getCount());
			}
		    }		    
		    break;

		/*
		 * When we join the lobby, the server will send us a
		 * message with all the characters that our player has.
		 */
		case NOTIFY_PLAYABLE_CHARACTERS: 
		    // we got updated with some character statistics...these
		    // are characters that the client is allowed to play

		    int numCharacters = message.getInt();

		    Collection<CharacterStats> characters =
			new ArrayList<CharacterStats>(numCharacters);
		    for (int i = 0; i < numCharacters; ++i) {
			characters.add(decodeStats(message));
		    }
		    
		    lobbyManager.setCharacters(characters);		    
		    break; 

		/*
		 * When we join a dungeon or move between levels in a
		 * dungeon, the server will send us a full listing of all
		 * the board spaces for the current dungeon level .  This
		 * is essentially a client-directed, bulk update method
		 * similar to the UPDATE_BOARD_SPACES command.
		 */		
		case NEW_BOARD:
		    // we got an update to reset the client's current
		    // board, so create a new client-side board based
		    // on the dimensions sent to us.  This board will
		    // have unknown floors and will be all empty.
		    int width = message.getInt();
		    int height = message.getInt();
		    ClientSideBoard board = new ClientSideBoard(width, height);

		    // next read off any positions the server will
		    // send to us.  These will include information
		    // about the floor type, creature and item at that
		    // location.
		    for (int i = 0; i < width; ++i) {
			for (int j = 0; j < height; ++j) {
			    readAndUpdateBoardSpace(board, i, j, message);
			}
		    }

		    dungeonManager.changeBoard(board);
		    break;

		/*
		 * The server will occassionaly send us text messages
		 * regarding the players state in the game.
		 */
		case NEW_SERVER_MESSAGE:
		    // we heard some message from the server
		    int mesgLength = message.getInt();
		    char[] chars = new char[mesgLength];
		    for (int i = 0; i < mesgLength; ++i)
			chars[i] = message.getChar();
		    String msg = new String(chars);
		    dungeonManager.hearMessage(msg);
		    break;
		    		    
		/*
		 * The client sent an unhandled command to the server,
		 * so the server has sent us back notice of what it
		 * was
		 */
		case UNHANDLED_COMMAND:
		    int encodedUnhandledCommand = message.getInt();
		    Command unhandled = Commands.decode(encodedUnhandledCommand);
		    int stringLength = message.getInt(); // unused
		    char[] arr = new char[stringLength];
		    for (int i = 0; i < stringLength; ++i) {
			arr[i] = message.getChar();
		    }
		    String channelName = new String(arr);
		    System.out.printf("This client sent the following " + 
				      "unhandled command %s to channel %s%n",
				      unhandled, channelName);
		    break;

		default:
		    System.out.printf("Received unknown command %s (%d) from the " +
				      "server%n", cmd, encodedCmd);	

		}
	    }
	    catch (IOException ioe) {
		System.out.println("IOException caught while processing " + 
				   "message from server");
		ioe.printStackTrace();
	    }
	}
    }

    private static CharacterStats decodeStats(ByteBuffer data) {
	int charNameLength = data.getInt();
	char[] arr = new char[charNameLength];
	for (int j = 0; j < charNameLength; ++j)
	    arr[j] = data.getChar();
	String characterName = new String(arr);
	return new CharacterStats(characterName,
				  data.getInt(), // str
				  data.getInt(), // int
				  data.getInt(), // dex
				  data.getInt(), // wis
				  data.getInt(), // con
				  data.getInt(), // chr
				  data.getInt(), // hp
				  data.getInt()); // max hp   
    }


    private static void readAndUpdateBoardSpace(ClientSideBoard board,
						int x, int y,
						ByteBuffer data) 
	throws IOException {

	int encodedFloorType = data.getInt();
	FloorType floorType = RoomInfo.decodeFloorType(encodedFloorType);
	board.setAt(x, y, floorType);

	// if the item name length is 0, then no item is present
	int itemNameLength = data.getInt();
	if (itemNameLength > 0) {
	    char[] arr = new char[itemNameLength];
	    for (int i = 0; i < itemNameLength; ++i) 
		arr[i] = data.getChar();
	    String itemName = new String(arr);
	    long itemId = data.getLong();
	    int encodedItemType = data.getInt();
	    ItemType itemType = ItemInfo.decodeItemType(encodedItemType);
	    board.setAt(x, y, new SimpleItem(itemType, itemId, itemName));
	}
	
	// the creature has a 0-length name, no creature is present
	int creatureNameLength = data.getInt();
	if (creatureNameLength > 0) {
	    char[] arr = new char[creatureNameLength];
	    for (int i = 0; i < creatureNameLength; ++i) 
		arr[i] = data.getChar();
	    String creatureName = new String(arr);
	    long creatureId = data.getLong();
	    int encodedCreatureType = data.getInt();
	    CreatureType creatureType = 
		CreatureInfo.decodeCreatureType(encodedCreatureType);
	    board.setAt(x, y, new SimpleCreature(creatureType, 
						 creatureId, creatureName));
	}
    }

    /**
     * Retrieves a serialized object from the given buffer.
     *
     * @param data the encoded object to retrieve
     */
    private static Object getObject(ByteBuffer data) throws IOException {
	try {
	    byte [] bytes = new byte[data.remaining()];
	    data.get(bytes);
	    
	    ByteArrayInputStream bin = new ByteArrayInputStream(bytes);
	    ObjectInputStream ois = new ObjectInputStream(bin);
	    return ois.readObject();
	} catch (ClassNotFoundException cnfe) {
	    throw new IOException(cnfe.getMessage(), cnfe);
	}
    }
    
    /**
     * A private helper that converts the map from the server (that
     * maps integers to byte arrays) into the form needed on the
     * client (that maps integers to images). The server sends the
     * byte array form because images aren't serializable.
     */
    private static Map<Integer,Image> convertMap(Map<Integer,byte[]> map) {
	Map<Integer,Image> newMap = new HashMap<Integer,Image>();
	
	// for each of the identified sprites, try to load the bytes
	// as a recognizable image format and store in the new map
	for (int identifier : map.keySet()) {
	    try {
		ByteArrayInputStream in =
		    new ByteArrayInputStream(map.get(identifier));
		newMap.put(identifier, ImageIO.read(in));
	    } catch (IOException ioe) {
		System.out.println("Failed to convert image: " + identifier);
		ioe.printStackTrace();
	    }
	}
	
	return newMap;
    }
    
    
    
    /**
     * The main-line for the client app.
     *
     * @param args command-line arguments, which are ignored
     */
    public static void main(String [] args) throws Exception {
	Client client = new Client();
	client.pack();
	client.setVisible(true);
	
	client.connect();
    }
    
    /**
     * Simple window monitor that quits the program when the main window
     * is closed.
     */
    class BasicWindowMonitor extends WindowAdapter {
	public void windowClosing(WindowEvent e) {
	    Window w = e.getWindow();
	    w.setVisible(false);
	    w.dispose();
	    System.exit(0);
	}
    }    
}
