package de.gost0r.pickupbot.pickup.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.gost0r.pickupbot.pickup.Match;
import de.gost0r.pickupbot.pickup.MatchStats;
import de.gost0r.pickupbot.pickup.MatchStats.Status;
import de.gost0r.pickupbot.pickup.PickupChannelType;
import de.gost0r.pickupbot.pickup.Player;
import de.gost0r.pickupbot.pickup.PlayerBan.BanReason;
import de.gost0r.pickupbot.pickup.Score;
import de.gost0r.pickupbot.pickup.server.ServerPlayer.ServerPlayerState;

public class ServerMonitor implements Runnable {
    private final static Logger LOGGER = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
	
	public static enum ServerState {
		WELCOME,
		WARMUP,
		LIVE,
		SCORE
	}
	
	private Server server;
	private Match match;
	
	private boolean stopped;
	
	int score[][] = new int[2][2]; 
	
	
	private List<ServerPlayer> players;
	private List<ServerPlayer> leavers;
	private String gameTime;
	private ServerState state;
	private boolean firstHalf;
	private boolean swapRoles;
	
	private boolean hasPaused;
	private boolean isPauseDetected;
	
	private long lastServerMessage = 0L;
	private long lastDiscordMessage = System.currentTimeMillis();
	
	private RconPlayersParsed prevRPP = new RconPlayersParsed();

	public ServerMonitor(Server server, Match match) {
		this.server = server;
		this.match = match;
		
		this.stopped = false;
		state = ServerState.WELCOME;
		
		firstHalf = true;
		
		hasPaused = false;
		isPauseDetected = false;
		
		players = new ArrayList<ServerPlayer>();
		leavers = new ArrayList<ServerPlayer>();
	}

	@Override
	public void run() {
		LOGGER.info("run() started on " + this.server.IP + ":" + this.server.port);
		try {
			while (!stopped) {
				observe();
				Thread.sleep(1000);
			}
		} catch (Exception e) {
			LOGGER.log(Level.WARNING, "Exception: ", e);
			match.getLogic().bot.sendMsg(match.getLogic().getChannelByType(PickupChannelType.ADMIN), "ServerMonitor " + e.toString());
		}
		LOGGER.info("run() ended");
	}
	
	public void stop() {
		stopped = true;
		LOGGER.info("stop() called");
	}
	
	private void observe() throws Exception {
		RconPlayersParsed rpp = parseRconPlayers();
		gameTime = rpp.gametime;
		
		if (prevRPP != null && prevRPP.gametime != null && rpp.gametime != null) {
			isPauseDetected = prevRPP.gametime.equals(rpp.gametime);
		}
		
		updatePlayers(rpp);	
		evaluateState(rpp);
		
		forceplayers();
		
		if (state == ServerState.WELCOME)
		{
			
		}
		else if (state == ServerState.WARMUP)
		{
			// Do nothing
		}
		else if (state == ServerState.LIVE)
		{
			
		}
		else if (state == ServerState.SCORE)
		{
			
		}
		checkNoshow();
		checkRagequit();
	}

	private void checkNoshow() throws Exception {
		List<Player> noshowPlayers = new ArrayList<Player>();
		String playerlist = "";
		for (Player player : match.getPlayerList()) {			
			if (match.getStats(player).getStatus() == MatchStats.Status.NOSHOW) {
				if (!playerlist.isEmpty()) {
					playerlist += "^3, ^1";
				}
				playerlist += player.getUrtauth();
				noshowPlayers.add(player);
			}
		}
		
		if (!playerlist.isEmpty()) {
			long timeleft = (match.getStartTime() + 300000L) - System.currentTimeMillis(); // 5min
			if (timeleft > 0) {
				String time = getTimeString(timeleft); 
				String sendString = "(" + time + ") Waiting for: ^1" + playerlist;
				sendServerMsg("say " + sendString);
				LOGGER.fine(sendString);
				String sendDiscordString = "(" + time + ") Please connect: " + getPlayerlistForDiscord(noshowPlayers);
				sendDiscordMsg(sendDiscordString);
				if (state == ServerState.WARMUP || state == ServerState.LIVE) {
					server.sendRcon("restart"); // restart map
					server.sendRcon("startserverdemo all");
				}
			} else if (timeleft < -300000L) { // if noshow timer ran out twice
				// we're way over time to accurately log a noshow, therefore simply abandon
				abandonMatch(MatchStats.Status.NOSHOW, new ArrayList<Player>());
			} else { // if the noshow time ran out
				abandonMatch(MatchStats.Status.NOSHOW, noshowPlayers);
			}
		}
	}
	
	private void checkRagequit() throws Exception {
		if (stopped) {
			return;
		}
		
		long earliestLeaver = -1L;
		String playerlist = "";
		List<Player> leaverPlayer = new ArrayList<Player>();
		for (ServerPlayer sp : leavers) {
			if (sp.player == null) continue;
			if (match.getStats(sp.player).getStatus() == MatchStats.Status.LEFT) {
				if (!playerlist.isEmpty()) {
					playerlist += ", ";
				}
				playerlist += sp.player.getUrtauth();
				leaverPlayer.add(sp.player);
				if (earliestLeaver == -1L || sp.timeDisconnect < earliestLeaver) {
					earliestLeaver = sp.timeDisconnect;
				}
			}
		}
		setAllPlayersStatus(leaverPlayer, Status.RAGEQUIT);
		
		if (leaverPlayer.size() > 0) {
			LOGGER.warning("Leavers: " + Arrays.toString(leaverPlayer.toArray()) + " earliest leaver: " + (earliestLeaver > 0 ? getTimeString(earliestLeaver) : "none"));
		}
		
		if (earliestLeaver > -1L) {
			boolean shouldPause = false;
			long timeleft = 0;
			if (state == ServerState.WELCOME) {
				timeleft = (earliestLeaver + 300000L) - System.currentTimeMillis(); // 5min
			} else if (state == ServerState.WARMUP) {
				timeleft = (earliestLeaver + 300000L) - System.currentTimeMillis(); // 5min
				server.sendRcon("restart"); // restart map
				server.sendRcon("startserverdemo all");
			} else if (state == ServerState.LIVE) {
				if (getRemainingSeconds() < 90 && isLastHalf()) {
					LOGGER.warning(getRemainingSeconds() + "s remaining, don't report.");
					setAllPlayersStatus(leaverPlayer, Status.LEFT);
					shouldPause = false;
					if (hasPaused && isPauseDetected) {
						if (state == ServerState.LIVE) {
							server.sendRcon("pause");
							server.sendRcon("startserverdemo all");
						}
						hasPaused = false;
					}
					return;
				} else {
					timeleft = (earliestLeaver + 180000L) - System.currentTimeMillis(); // 3min
					shouldPause = true;
				}
			} else if (state == ServerState.SCORE) {
				// TODO: need to remove them from the leaver list though.
				//setAllPlayersStatus(leaverPlayer, Status.LEFT);
				return; // ignore leavers in the score screen
			}
			if (timeleft > 0) {
				// pause if someone leaves
				if (!hasPaused && shouldPause) {
					if (!isPauseDetected) {
						server.sendRcon("pause");
						server.sendRcon("startserverdemo all");
					}
					hasPaused = true;
				}
				
				// send message
				String time = getTimeString(timeleft); 
				String sendServerString = "(" + time + ") Time to reconnect for: ^1" + playerlist;
				sendServerMsg("say " + sendServerString);
				LOGGER.fine(sendServerString);
				String sendDiscordString = "(" + time + ") Time to reconnect for: " + getPlayerlistForDiscord(leaverPlayer);
				sendDiscordMsg(sendDiscordString);
			} else {
				abandonMatch(MatchStats.Status.LEFT, leaverPlayer);
			}
		}
		
		// unpause if noone left
		if (leaverPlayer.size() == 0) {
			if (hasPaused && isPauseDetected) {
				if (state == ServerState.LIVE) {
					server.sendRcon("pause");
					server.sendRcon("startserverdemo all");
				}
				hasPaused = false;
			}
		}
	}
	
	private String getPlayerlistForDiscord(List<Player> playerlist) {
		if (playerlist.size() > 0) {
			String playerstring = "";
			for (Player player : playerlist) {
				playerstring += " " + player.getDiscordUser().getMentionString();
			}
			return playerstring.substring(1);
		}
		return "";
	}

	private void setAllPlayersStatus(List<Player> playerList, Status status) {
		for (Player player : playerList) {
			match.getStats(player).updateStatus(status);
		}
	}

	private void saveStats(int[] scorex) throws Exception {
		int half = firstHalf ? 0 : 1;
		
		score[half] = scorex;
		
		// reset matchstats to previous
		for (ServerPlayer sp : prevRPP.players) {
			for (ServerPlayer player : players) {
				if (sp.equals(player)) {
					player.copy(sp);
					continue;
				}
			}
		}

		// save playerscores
		for (ServerPlayer player : players) {
			try {
				if (player.player != null && match.isInMatch(player.player)) {
					match.getStats(player.player).score[half].score = Integer.valueOf(player.ctfstats.score);
					match.getStats(player.player).score[half].deaths = Integer.valueOf(player.ctfstats.deaths);
					match.getStats(player.player).score[half].assists = Integer.valueOf(player.ctfstats.assists);
					match.getStats(player.player).score[half].caps = Integer.valueOf(player.ctfstats.caps);
					match.getStats(player.player).score[half].returns = Integer.valueOf(player.ctfstats.returns);
					match.getStats(player.player).score[half].fc_kills = Integer.valueOf(player.ctfstats.fc_kills);
					match.getStats(player.player).score[half].stop_caps = Integer.valueOf(player.ctfstats.stop_caps);
					match.getStats(player.player).score[half].protect_flag = Integer.valueOf(player.ctfstats.protect_flag);
				}
			} catch (NumberFormatException e) {
				LOGGER.log(Level.WARNING, "Exception: ", e);
			}
		}
		
	}

	private void forceplayers() throws Exception {
		
		for (ServerPlayer sp : players) {
			if (sp.state == ServerPlayerState.Connected || sp.state == ServerPlayerState.Reconnected) {
				Player player = Player.get(sp.auth);				
				if (player != null && match.isInMatch(player)) {
					sp.player = player;
					match.getStats(player).updateStatus(MatchStats.Status.PLAYING);
					match.getStats(player).updateIP(sp.ip);
				} else if (player != null && player.getDiscordUser().hasAdminRights()) {
					// PLAYER IS AN ADMIN, DONT FORCE/KICK HIM
					continue;
				} else { // if player not authed, auth not registered or not playing in this match -> kick
					LOGGER.info("Didn't find " + sp.name + " (" + sp.auth + ") signed up for this match  -> kick");
					server.sendRcon("kick " + sp.id); // You are not authed and/or not signed up for this match.");
					sp.state = ServerPlayerState.Disconnected;
					continue;
				}
				
				String team = match.getTeam(player);
				if (team != null && state != ServerState.SCORE)
				{
					String oppTeam = team.equalsIgnoreCase("red") ? "blue" : "red";
					if (!sp.team.equalsIgnoreCase(team) && firstHalf)
					{
						LOGGER.info("Player " + sp.name + " (" + sp.auth + ") is in the wrong team. Supposed to be: " + team.toUpperCase() + " but currently " + sp.team);
						server.sendRcon("forceteam " + sp.id + " " + team.toUpperCase());
					}
					else if (!sp.team.equalsIgnoreCase(oppTeam) && !firstHalf) // we should have switched teams -.-
					{
						LOGGER.info("Player " + sp.name + " (" + sp.auth + ") is in the wrong team. Supposed to be: " + oppTeam.toUpperCase() + " but currently " + sp.team);
						server.sendRcon("forceteam " + sp.id + " " + oppTeam.toUpperCase());
					}
				}
			
			} else { // not active
				if (sp.player != null && match.getStats(sp.player).getStatus() != MatchStats.Status.LEFT) { 
					match.getStats(sp.player).updateIP(sp.ip);
					match.getStats(sp.player).updateStatus(MatchStats.Status.LEFT);
				}
			}
		}
	}
	


	private void evaluateState(RconPlayersParsed rpp) throws Exception {
		if (state == ServerState.WELCOME)
		{
			if (rpp.matchready[0] && rpp.matchready[1] && rpp.warmupphase)
			{
				state = ServerState.WARMUP;
				LOGGER.info("SWITCHED WELCOME -> WARMUP");
			}
			else if (rpp.matchready[0] && rpp.matchready[1] && !rpp.warmupphase)
			{
				state = ServerState.LIVE;
				LOGGER.info("SWITCHED WELCOME -> LIVE");
			}
		}
		else if (state == ServerState.WARMUP)
		{
			if (rpp.matchready[0] && rpp.matchready[1] && !rpp.warmupphase)
			{
				state = ServerState.LIVE;
				LOGGER.info("SWITCHED WARMUP -> LIVE");
			}
			else if (!rpp.matchready[0] || !rpp.matchready[1])
			{
				state = ServerState.WELCOME;
				LOGGER.info("SWITCHED WARMUP -> WELCOME");
			}
		}
		else if (state == ServerState.LIVE)
		{
			if (rpp.gametime != null && rpp.gametime.equals("00:00:00"))
			{
				state = ServerState.SCORE;
				LOGGER.info("SWITCHED LIVE -> SCORE");
			}
		}
		else if (state == ServerState.SCORE)
		{
			if (rpp.warmupphase) {
				if (rpp.matchready[0] && rpp.matchready[1])
				{
					state = ServerState.WARMUP;
					LOGGER.info("SWITCHED SCORE -> WARMUP");
				} else {
					state = ServerState.WELCOME;
					LOGGER.info("SWITCHED SCORE -> WELCOME");
				}
				handleScoreTransition();
			} else {
				if (getPlayerCount("red") == 0 || getPlayerCount("blue") == 0 || !rpp.matchready[0] || !rpp.matchready[1]) {
					state = ServerState.WELCOME;
					LOGGER.info("SWITCHED SCORE -> WELCOME");
					handleScoreTransition();
				}
			}
		}
		prevRPP = rpp;
	}

	private int getPlayerCount(String team) throws Exception {
		int count = 0;
		for (ServerPlayer player : players) {
			if (player.state == ServerPlayer.ServerPlayerState.Connected || player.state == ServerPlayer.ServerPlayerState.Reconnected) {
				if (player.team.equalsIgnoreCase(team)) {
					count++;
				}
			}
		}
		return count;
	}

	private void handleScoreTransition() throws Exception {		
		swapRoles = getSwapRoles();
		
		saveStats(prevRPP.scores);
		if (!swapRoles || (swapRoles && !firstHalf)) {
			endGame();
		} else {
			firstHalf = false;
		}
	}

	private void updatePlayers(RconPlayersParsed rpp) throws Exception {
		List<ServerPlayer> oldPlayers = new ArrayList<ServerPlayer>(players);
		List<ServerPlayer> newPlayers = new ArrayList<ServerPlayer>();
		
		for (ServerPlayer player : rpp.players) {
			
			if (player.state == ServerPlayerState.Connecting) continue; // ignore connecting players
			
			if (player.auth.equals("---")) {
				requestAuth(player);
			}
			
			// find player in serverplayerlist
			ServerPlayer found = null;
			for (ServerPlayer player_x : players) {
				if (player.equals(player_x)) {
					player_x.copy(player);
					found = player_x;
					break;
				}
			}
			
			if (found != null) {
				if (found.state == ServerPlayerState.Disconnected) {
					found.state = ServerPlayerState.Reconnected;
					LOGGER.info("Player " + found.name + " (" + found.auth + ") reconnected.");
					found.timeDisconnect = -1L;
				}
				oldPlayers.remove(found);
			} else {
				LOGGER.info("Player " + player.name + " (" + player.auth + ") connected.");
				newPlayers.add(player);
			}
		}

		for (ServerPlayer player : oldPlayers) {
			if (player.state != ServerPlayerState.Disconnected) {
				player.state = ServerPlayerState.Disconnected;
				player.timeDisconnect = System.currentTimeMillis();
				LOGGER.info("Player " + player.name + " (" + player.auth + ") disconnected.");
			}
		}
		
		leavers = oldPlayers;
		
		for (ServerPlayer player : newPlayers) {
			players.add(player);
		}
	}
	
	private void requestAuth(ServerPlayer player) throws Exception {
		String replyAuth = server.sendRcon("auth-whois " + player.id);
		LOGGER.fine(replyAuth);
		if (replyAuth != null && !replyAuth.isEmpty()) {
			if (replyAuth.startsWith("Client in slot")) return;
			String[] splitted = replyAuth.split(" ");
			player.auth = splitted[8];
			player.auth = player.auth.isEmpty() ? "---" : player.auth;
		} else {
			requestAuth(player);
			LOGGER.severe("requesting auth again for " + player.name);
		}
	}

	private boolean getSwapRoles() throws Exception {
		String swaproles = server.sendRcon("g_swaproles");
		LOGGER.fine(swaproles);
		String[] split = swaproles.split("\"");
		if (split.length > 4) {
			return split[3].equals("1^7");
		}
		return false;
	}

	private RconPlayersParsed parseRconPlayers() throws Exception {

		RconPlayersParsed rpp = new RconPlayersParsed();
		
		String playersString = server.sendRcon("players");
//		LOGGER.fine("rcon players: >>>" + playersString + "<<<");
		String[] stripped = playersString.split("\n");

		// hax to avoid empty
		if (stripped.length == 1) {
			LOGGER.warning("Corrupted RPP (too short), taking prevRPP instead");
			return prevRPP;
		}
		
		boolean awaitsStats = false;		
		for (String line : stripped)
		{
			LOGGER.fine("line " + line);
			if (line.isEmpty()) continue;
			if (line.equals("print")) continue;
			if (line.equals("==== ShutdownGame ====")) break;
			
			if (line.startsWith("Map:"))
			{
				rpp.map = line.split(" ")[1];
			}
			else if (line.startsWith("Players"))
			{
				rpp.playercount = Integer.valueOf(line.split(" ")[1]);				
			}
			else if (line.startsWith("GameType"))
			{
				rpp.gametype = line.split(" ")[1];
			}
			else if (line.startsWith("Scores"))
			{
				rpp.scores[0] = Integer.valueOf(line.split(" ")[1].split(":")[1]);
				rpp.scores[1] = Integer.valueOf(line.split(" ")[2].split(":")[1]);
			}
			else if (line.startsWith("MatchMode"))
			{
				rpp.matchmode = line.split(" ")[1].equals("ON") ? true : false;
			}
			else if (line.startsWith("MatchReady"))
			{
				rpp.matchready[0] = line.split(" ")[1].split(":")[1].equals("YES") ? true : false;
				rpp.matchready[1] = line.split(" ")[2].split(":")[1].equals("YES") ? true : false;
			}
			else if (line.startsWith("WarmupPhase"))
			{
				rpp.warmupphase = line.split(" ")[1].equals("YES") ? true : false;
			}
			else if (line.startsWith("GameTime"))
			{
				rpp.gametime = line.split(" ")[1];
			}
			else if (line.startsWith("RoundTime"))
			{
				rpp.roundtime = line.split(" ")[1];
			}
			else if (line.startsWith("Half"))
			{
				rpp.half = line.split(" ")[1];
			}
			else
			{
				String[] splitted = line.split(" ");
//				LOGGER.severe("splitted = " + Arrays.toString(splitted));
				
				if (splitted[0].equals("[connecting]")) continue;
				
				if (splitted[0].equals("CTF:") && awaitsStats) {
					// ctfstats
					((CTF_Stats) rpp.players.get(rpp.players.size()-1).ctfstats).caps = splitted[1].split(":")[1];
					((CTF_Stats) rpp.players.get(rpp.players.size()-1).ctfstats).returns = splitted[2].split(":")[1];
					((CTF_Stats) rpp.players.get(rpp.players.size()-1).ctfstats).fc_kills = splitted[3].split(":")[1];
					((CTF_Stats) rpp.players.get(rpp.players.size()-1).ctfstats).stop_caps = splitted[4].split(":")[1];
					((CTF_Stats) rpp.players.get(rpp.players.size()-1).ctfstats).protect_flag = splitted[5].split(":")[1];
					awaitsStats = false;
				}
				else if (splitted[0].equals("BOMB:") && awaitsStats)
				{
					/*	BOMB: PLT:%i SBM:%i PKB:%i DEF:%i KBD:%i KBC:%i PKBC:%i
						>BOMB_PLANT
						>BOMB_BOOM
						>BOMBED
						>BOMB_DEFUSE
						>KILL_DEFUSE
						>KILL_BC
						>PROTECT_BC
					*/
					awaitsStats = false;
				}
				else if (rpp.players.size() < rpp.playercount) 
				{
					ServerPlayer sp = new ServerPlayer();
					sp.id = splitted[0].split(":")[0];
					sp.name = splitted[0].split(":").length > 1 ? splitted[0].split(":")[1] : "unknown";
					sp.team = splitted[1].split(":")[1];
					sp.ctfstats.score = splitted[2].split(":")[1];
					sp.ctfstats.deaths = splitted[3].split(":")[1];
					sp.ctfstats.assists = splitted[4].split(":")[1];
					sp.ping = splitted[5].split(":")[1];
					sp.auth = splitted[6].split(":")[1];
					sp.ip = splitted[7].split(":")[1];
					
//					if (sp.ping.equals("0")) {
//						sp.state = ServerPlayerState.Connecting;
//					} else {
						sp.state = ServerPlayerState.Connected;
//					}
					
					rpp.players.add(sp);
					awaitsStats = true;
				}
				
			}
		}
		// hax to avoid empty
		if (rpp.map == null || rpp.playercount != rpp.players.size()) {
			LOGGER.warning("Corrupted RPP, taking prevRPP instead");
			return prevRPP;
		}
		return rpp;
	}
	
	private void endGame() throws Exception {
		calcStats();
		match.end();
		match.getServer().sendRcon("uploadserverdemo now");
		stop();
	}
	
	private void calcStats() throws Exception {
		int redscore = score[0][0] + score[1][1]; //score_red_first + score_blue_second;
		int bluescore = score[0][1] + score[1][0]; //score_blue_first + score_red_second;
		int[] finalscore = { redscore, bluescore };
		LOGGER.info("Score: " + Arrays.toString(finalscore));
        for (Player player : match.getPlayerList()) {
        	if (player != null) {
        		calcElo(player, finalscore);
        	}
        }
        match.setScore(finalscore);
	}
	
	public void calcElo(Player player, int[] score) throws Exception {
		int team = match.getTeam(player).equalsIgnoreCase("red") ? 0 : 1;
		int opp = (team + 1) % 2;
		
		int eloSelf = player.getElo();
		int eloOpp = match.getElo()[opp];
		
		// 1 win, 0.5 draw, 0 loss
		double w = score[team] > score[opp] ? 1d : (score[team] < score[opp] ? 0d : 0.5d);
		
		double tSelf = Math.pow(10d, eloSelf/400d);
		double tOpp = Math.pow(10d, eloOpp/400d);
		double e = tSelf / (tSelf + tOpp);
		
		double result = 32d * (w - e);
		int elochange = (int) Math.floor(result);
		
		
		// rate players based on score
		Score[] playerStats = match.getStats(player).score;
		int performance = 0;
		for (Score stats : playerStats) {
			performance += stats.score + stats.assists;
		}
		float performanceRating = 1;
		if (elochange > 0) {
			performanceRating += ((float) performance / 100);
		} else if (elochange < 0)  {
			performanceRating -= ((float) performance / 100);
		}
		performanceRating = Math.min(1.75f, Math.max(0.25f, performanceRating));
		elochange = (int) Math.floor(result * performanceRating);
		
				
		int newelo = player.getElo() + elochange;
		LOGGER.info("ELO player: " + player.getUrtauth() + " old ELO: " + player.getElo() + " new ELO: " + newelo + " (" + (!String.valueOf(elochange).startsWith("-") ? "+" : "") + elochange + ")");
		player.addElo(elochange);
	}
	
	public void surrender(int teamid) throws Exception {
		// save stats
		if (state == ServerState.LIVE || state == ServerState.SCORE) {
			saveStats(new int[] {0, 0}); // score don't matter as we override them. don't matter
		}
		
		int[] scorex = new int[2];
		scorex[teamid] = 0;
		scorex[(teamid + 1) % 2] = 15;
		score[0] = scorex;
		score[1] = new int[] {0, 0};

		calcStats();
		stop();
	}
	


	private void abandonMatch(Status status, List<Player> involvedPlayers) throws Exception {
		// save stats
		if (state == ServerState.LIVE || state == ServerState.SCORE) {
			saveStats(new int[] {0, 0}); // score don't matter as we override them. don't matter
		}
		
		for (Player player : match.getPlayerList()) {
			player.setEloChange(0);
		}
		
		for (Player player : involvedPlayers) {			
			BanReason reason = BanReason.NOSHOW;
			if (match.getStats(player).getStatus() == Status.NOSHOW) {
				reason = BanReason.NOSHOW;
			} else if (match.getStats(player).getStatus() == Status.RAGEQUIT) {
				reason = BanReason.RAGEQUIT;
			}
			match.getLogic().autoBanPlayer(player, reason);
		}
		
		String reason = status == Status.NOSHOW ? "NOSHOW" : status == Status.RAGEQUIT ? "RAGEQUIT" : "UNKNOWN";
		String sendString = "Abandoning due to ^1" + reason + "^3. You may sign up again.";
		
		for (int i = 0; i < 3; i++) {
			server.sendRcon("say " + sendString);
		}
		LOGGER.info(sendString);
		
		stop();
		match.abandon(status, involvedPlayers);
	}

	public String getGameTime() {
		return gameTime;
	}

	public String getScore() {
		
		int[] total = new int[] { 0, 0 };
		if (!firstHalf) {
			total[0] = score[0][0];
			total[1] = score[0][1];
		}
		
		if (state == ServerState.LIVE || state == ServerState.SCORE) {
			int team1 = firstHalf ? 0 : 1;
			int team2 = firstHalf ? 1 : 0;
			total[0] += prevRPP.scores[team1];
			total[1] += prevRPP.scores[team2];
		}
		
		return String.valueOf(total[0]) + "-" + String.valueOf(total[1]);
	}
	
	private String getTimeString(long time) throws Exception {
		time /= 1000L; // time in s

		String min = String.valueOf((int) (Math.floor(time / 60d)));
		String sec = String.valueOf((int) (Math.floor(time % 60d)));
		min = min.length() == 1 ? "0" + min : min;
		sec = sec.length() == 1 ? "0" + sec : sec;
		
		return min + ":" + sec;
	}
	
	private int getRemainingSeconds() throws Exception {
		String[] split = gameTime.split(":");
		return Integer.valueOf(split[0]) * 3600 + Integer.valueOf(split[1]) * 60 + Integer.valueOf(split[2]);
	}

	private boolean isLastHalf() throws Exception {
		if (this.prevRPP != null) {
			return this.prevRPP.half == null || !firstHalf;
		}
		return false;
	}
	
	private void sendServerMsg(String text) {
		if (lastServerMessage + 10000L < System.currentTimeMillis()) {
			server.sendRcon(text);
			lastServerMessage = System.currentTimeMillis();
		}
	}
	
	private void sendDiscordMsg(String text) {
		if (lastDiscordMessage + 60000L < System.currentTimeMillis()) {
			match.getLogic().bot.sendMsg(match.getLogic().getChannelByType(PickupChannelType.PUBLIC), text);
			lastDiscordMessage = System.currentTimeMillis();
		}
	}

	public ServerState getState() {
		return state;
	}
}
