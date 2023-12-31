package hexed;

import arc.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import hexed.HexData.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.core.NetServer.*;
import mindustry.entities.Units;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.game.Schematic.*;
import mindustry.game.Teams.*;
import mindustry.gen.*;
import mindustry.mod.*;
import mindustry.net.Packets.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.*;

import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import static arc.util.Log.*;
import static mindustry.Vars.*;

public class HexedMod extends Plugin{
    //in seconds
    public static final float spawnDelay = 60 * 4;
    //health requirement needed to capture a hex; no longer used
    public static final float healthRequirement = 3500;
    //item requirement to captured a hex
    public static final int itemRequirement = 210;

    public static final int messageTime = 1;
    //in ticks: 60 minutes
    private final static int roundTime = 60 * 60 * 3600;
    //in ticks: 3 minutes
    private final static int leaderboardTime = 60 * 60 * 2;

    private final static int updateTime = 60 * 2;

    private final static int timerBoard = 0, timerUpdate = 1, timerWinCheck = 2;

    private final Rules rules = new Rules();
    private Interval interval = new Interval(5);

    private HexData data;
    private boolean restarting = false, registered = false;

    private Schematic start;
    private double counter = 0f;
    private int lastMin;

    /** Maps token -> team ID */
    private HashMap<String, Integer> tokenToId = new HashMap<>();
    /** Maps team ID -> token */
    private IntMap<byte[]> idToToken = new IntMap<>();

    @Override
    public void init(){
        rules.pvp = true;
        rules.pvpAutoPause = false;
        rules.tags.put("hexed", "true");
        rules.canGameOver = false;
        rules.polygonCoreProtection = true;

        //for further configuration, use `ruless add <name> <value...>`
        /*
        rules.loadout = ItemStack.list(Items.copper, 300, Items.lead, 500, Items.graphite, 150, Items.metaglass, 150, Items.silicon, 150, Items.plastanium, 50);
        rules.buildCostMultiplier = 1f;
        rules.buildSpeedMultiplier = 1f / 2f;
        rules.blockHealthMultiplier = 1.2f;
        rules.unitBuildSpeedMultiplier = 1f;
        rules.unitDamageMultiplier = 1.1f;
        */

        start = Schematics.readBase64("bXNjaAB4nE2SgY7CIAyGC2yDsXkXH2Tvcq+AkzMmc1tQz/j210JpXDL8hu3/lxYY4FtBs4ZbBLvG1ync4wGO87bvMU2vsCzTEtIlwvCxBW7e1r/43hKYkGY4nFN4XqbfMD+29IbhvmHOtIc1LjCmuIcrfm3X9QH2PofHIyYY5y3FaX3OS3ze4fiRwX7dLa5nDHTPddkCkT3l1DcA/OALihZNq4H6NHnV+HZCVshJXA9VYZC9kfVU+VQGKSsbjVT1lOgp1qO4rGIo9yvnquxH1ORIohap6HVIDbtpaNlDi4cWD80eFJdrNhbJc8W61Jzdqi/3wrRIRii7GYdelvWMZDQs1kNbqtYe9/KuGvDX5zD6d5SML66+5dwRqXgQee5GK3Edxw1ITfb3SJ71OomzUAdjuWsWqZyJavd8Issdb5BqVbaoGCVzJqrddaUGTWSFHPs67m6H5HlaTqbqpFc91Kfn+2eQSp9pr96/Xtx6cevZjeKKDuUOklvvXy9uPGdNZFjZi7IXZS/n8Hyf/wFbjj/q");

        Events.run(Trigger.update, () -> {
            if(active()){
                data.updateStats();

                for(Player player : Groups.player){
                    if(player.team() != Team.derelict && player.team().cores().isEmpty()){
                        player.clearUnit();
                        killTiles(player.team());
                        Call.sendMessage("[yellow](!)[] [accent]" + player.name + "[lightgray] has been eliminated![yellow] (!)");
                        Call.infoMessage(player.con, "Your cores have been destroyed. You are defeated.");
                        player.team(Team.derelict);
                    }

                    if(player.team() == Team.derelict){
                        player.clearUnit();
                    }else if(data.getControlled(player).size == data.hexes().size){
                        endGame();
                        break;
                    }
                }

                int minsToGo = (int)(roundTime - counter) / 60 / 60;
                if(minsToGo != lastMin){
                    lastMin = minsToGo;
                }

                if(interval.get(timerBoard, leaderboardTime)){
                    Call.infoToast(getLeaderboard(), 15f);
                }

                if(interval.get(timerUpdate, updateTime)){
                    data.updateControl();
                }

                counter += Time.delta;

                //kick everyone and restart w/ the script
                if(counter > roundTime && !restarting){
                    endGame();
                }
            }else{
                counter = 0;
            }
        });

        Events.on(BlockDestroyEvent.class, event -> {
            //reset last spawn times so this hex becomes vacant for a while.
            if(event.tile.block() instanceof CoreBlock){
                Hex hex = data.getHex(event.tile.pos());

                if(hex != null){
                    //update state
                    hex.spawnTime.reset();
                    hex.updateController();
                }
            }
        });

        Events.on(PlayerLeave.class, event -> {
            if(active() && event.player.team() != Team.derelict){
                Seq<Player> players = data.getLeaderboard();
                for (Player player : players) {
                    // don't kill units if there's a player left on that team
                    if (player != event.player && player.team() == event.player.team()) {
                        return;
                    }
                }
                var token = idToToken.get(event.player.team().id);
                if (token != null) {
                    idToToken.remove(event.player.team().id);
                    tokenToId.remove(bytesToHex(token).toLowerCase());
                }
                killTiles(event.player.team());
            }
        });

        Events.on(PlayerJoin.class, event -> {
            return;
        });

        Events.on(ProgressIncreaseEvent.class, event -> updateText(event.player));

        Events.on(HexCaptureEvent.class, event -> updateText(event.player));

        Events.on(HexMoveEvent.class, event -> updateText(event.player));

        TeamAssigner prev = netServer.assigner;
        netServer.assigner = (player, players) -> {
            if(active()){
                return Team.derelict;
            } else {
                return prev.assign(player, players);
            }
        };
    }

    void updateText(Player player){
        HexTeam team = data.data(player);

        StringBuilder message = new StringBuilder("[white]Hex #" + team.location.id + "\n");

        if(!team.lastMessage.get()) return;

        if(team.location.controller == null){
            if(team.progressPercent > 0){
                message.append("[lightgray]Capture progress: [accent]").append((int)(team.progressPercent)).append("%");
            }else{
                message.append("[lightgray][[Empty]");
            }
        }else if(team.location.controller == player.team()){
            message.append("[yellow][[Captured]");
        }else if(team.location != null && team.location.controller != null && data.getPlayer(team.location.controller) != null){
            message.append("[#").append(team.location.controller.color).append("]Captured by ").append(data.getPlayer(team.location.controller).name);
        }else{
            message.append("<Unknown>");
        }

        Call.setHudText(player.con, message.toString());
    }

    @Override
    public void registerServerCommands(CommandHandler handler){
        handler.register("hexed", "Begin hosting with the Hexed gamemode.", args -> {
            if(!state.is(State.menu)){
                Log.err("Stop the server first.");
                return;
            }

            data = new HexData();

            logic.reset();
            Log.info("Generating map...");
            HexedGenerator generator = new HexedGenerator();
            world.loadGenerator(Hex.size, Hex.size, generator);
            data.initHexes(generator.getHex());
            info("Map generated.");
            state.rules = rules.copy();
            logic.play();
            netServer.openServer();
        });

        handler.register("countdown", "Get the hexed restart countdown.", args -> {
            Log.info("Time until round ends: &lc@ minutes", (int)(roundTime - counter) / 60 / 60);
        });

        handler.register("end", "End the game.", args -> endGame());

        handler.register("r", "Restart the server.", args -> System.exit(2));
    }

    private static final byte[] HEX_ARRAY = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);
    private static String bytesToHex(byte[] bytes) {
        byte[] hexChars = new byte[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars, StandardCharsets.UTF_8);
    }

    private static byte[] hexToBytes(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    @Override
    public void registerClientCommands(CommandHandler handler){
        if(registered) return;
        registered = true;

        /*
        handler.<Player>register("eclipse", "Create an Eclipse", (args, player) -> {
            var u = UnitTypes.eclipse.create(player.team());
            u.set(player.unit().x, player.unit().y);
            u.add();
        });
        */

        handler.<Player>register("pow", "Sacrifice an Eclipse", (args, player) -> {
            var success = Units.any(0, 0, (float)world.width() * tilesize, (float)world.height() * tilesize, u -> u.team == player.team() && u.type == UnitTypes.eclipse);
            var token = idToToken.get(player.team().id);
            if (!success || token == null) {
                Call.infoMessage(player.con, "Hex harder.");
                player.sendMessage("[scarlet]Hex harder.");
                return;
            }
            try {
                // compute hmac
                /* python equivalent:
                 * key = bytes.fromhex("e6a0e243b52e9c92643bd86a36fd18e6bc366b688d4bfc766c73d673515d3b14cb6962d04836c46410adf3d373caebabf2412ace00aab35e20d7d7befe78d4f2")
                 * hmac.digest(key, token, 'sha256').hex()[:12]
                 * */
                byte[] ikey = this.hexToBytes("d096d4758318aaa4520dee5c00cb2ed08a005d5ebb7dca405a45e045676b0d22fd5f54e67e00f252269bc5e545fcdd9dc4771cf8369c856816e1e188c84ee2c4");
                byte[] okey = this.hexToBytes("bafcbe1fe972c0ce386784366aa144bae06a3734d117a02a302f8a2f0d01674897353e8c146a98384cf1af8f2f96b7f7ae1d76925cf6ef027c8b8be2a22488ae");
                MessageDigest outer = MessageDigest.getInstance("SHA-256");
                MessageDigest inner = MessageDigest.getInstance("SHA-256");
                inner.update(ikey);
                inner.update(token);
                outer.update(okey);
                outer.update(inner.digest());
                var hmac = outer.digest();
                String hmacStr = this.bytesToHex(outer.digest());
                String hmactrunc = hmacStr.substring(0, 12);

                Call.infoMessage(player.con, hmactrunc);
                player.sendMessage("[scarlet]" + hmactrunc);

                idToToken.remove(player.team().id);
                tokenToId.remove(token);
                killTiles(player.team());
                player.unit().kill();
                for (Player teamPlayer : player.team().data().players) {
                    teamPlayer.team(Team.derelict);
                }
            } catch (NoSuchAlgorithmException ex) {
                Call.infoMessage(player.con, "Can't hex.");
                player.sendMessage("[scarlet]Can't hex.");
            }
        });

        handler.<Player>register("join", "<token>", "Join a team using your token.", (args, player) -> {
            String strToken = args[0].toLowerCase();
            byte[] token = this.hexToBytes(strToken);
            // token is 8 bytes, token[1] is a parity byte
            if (token.length != 8) {
                player.sendMessage("[scarlet]Invalid token.");
                return;
            }
            if ((token[0] ^ token[2] ^ token[3] ^ token[4] ^ token[5] ^ token[6] ^ token[7]) != token[1]) {
                player.sendMessage("[scarlet]Invalid token.");
                return;
            }
            if (player.team() != Team.derelict) {
                player.sendMessage("[scarlet]Must be spectating to join a team.");
                return;
            }
            var teamId = tokenToId.get(strToken);
            if (teamId == null) {
                Seq<Player> players = data.getLeaderboard();
                for(Team team : Team.all){
                    if(team.id > 5 && !team.active() && !players.contains(p -> p.team() == team) && !data.data(team).dying && !data.data(team).chosen) {
                        teamId = team.id;
                        tokenToId.put(strToken, teamId);
                        idToToken.put(teamId, token);
                        break;
                    }
                }
            }
            if (teamId == null) {
                Call.infoMessage(player.con, "There are currently no empty hex spaces available.\nAssigning into spectator mode.");
                return;
            }
            var team = Team.all[teamId];
            player.team(team);
            if (data.data(team).chosen == false) {
                data.data(team).chosen = true;
                Seq<Hex> copy = data.hexes().copy();
                copy.shuffle();
                Hex hex = copy.find(h -> h.controller == null && h.spawnTime.get());
                if(hex != null) {
                    loadout(player, hex.x, hex.y);
                    hex.findController();
                } else {
                    Call.infoMessage(player.con, "There are currently no empty hex spaces available.\nAssigning into spectator mode.");
                    player.unit().kill();
                    player.team(Team.derelict);
                }
            }
        });

        handler.<Player>register("captured", "Display the number of hexes you have captured.", (args, player) -> {
            if(player.team() == Team.derelict){
                player.sendMessage("[scarlet]You're spectating.");
            }else{
                player.sendMessage("[lightgray]You've captured[accent] " + data.getControlled(player).size + "[] hexes.");
            }
        });

        handler.<Player>register("leaderboard", "Display the leaderboard", (args, player) -> {
            player.sendMessage(getLeaderboard());
        });

        handler.<Player>register("hexstatus", "Get hex status at your position.", (args, player) -> {
            Hex hex = data.data(player).location;
            if(hex != null){
                hex.updateController();
                StringBuilder builder = new StringBuilder();
                builder.append("| [lightgray]Hex #").append(hex.id).append("[]\n");
                builder.append("| [lightgray]Owner:[] ").append(hex.controller != null && data.getPlayer(hex.controller) != null ? data.getPlayer(hex.controller).name : "<none>").append("\n");
                for(TeamData data : state.teams.getActive()){
                    if(hex.getProgressPercent(data.team) > 0){
                        builder.append("|> [accent]").append(this.data.getPlayer(data.team).name).append("[lightgray]: ").append((int)hex.getProgressPercent(data.team)).append("% captured\n");
                    }
                }
                player.sendMessage(builder.toString());
            }else{
                player.sendMessage("[scarlet]No hex found.");
            }
        });
    }

    void endGame(){
        if(restarting) return;

        restarting = true;
        Seq<Player> players = data.getLeaderboard();
        StringBuilder builder = new StringBuilder();
        for(int i = 0; i < players.size && i < 3; i++){
            if(data.getControlled(players.get(i)).size > 1){
                builder.append("[yellow]").append(i + 1).append(".[accent] ").append(players.get(i).name)
                .append("[lightgray] (x").append(data.getControlled(players.get(i)).size).append(")[]\n");
            }
        }

        if(!players.isEmpty()){
            boolean dominated = data.getControlled(players.first()).size == data.hexes().size;

            for(Player player : Groups.player){
                Call.infoMessage(player.con, "[accent]--ROUND OVER--\n\n[lightgray]"
                + (player == players.first() ? "[accent]You[] were" : "[yellow]" + players.first().name + "[lightgray] was") +
                " victorious, with [accent]" + data.getControlled(players.first()).size + "[lightgray] hexes conquered." + (dominated ? "" : "\n\nFinal scores:\n" + builder));
            }
        }

        Log.info("&ly--SERVER RESTARTING--");
        Time.runTask(60f * 10f, () -> {
            netServer.kickAll(KickReason.serverRestarting);
            Time.runTask(5f, () -> System.exit(2));
        });
    }

    String getLeaderboard(){
        StringBuilder builder = new StringBuilder();
        builder.append("[accent]Leaderboard\n[scarlet]").append(lastMin).append("[lightgray] mins. remaining\n\n");
        int count = 0;
        for(Player player : data.getLeaderboard()){
            builder.append("[yellow]").append(++count).append(".[white] ")
            .append(player.name).append("[orange] (").append(data.getControlled(player).size).append(" hexes)\n[white]");

            if(count > 4) break;
        }
        return builder.toString();
    }

    void killTiles(Team team){
        data.data(team).dying = true;
        Time.runTask(8f, () -> data.data(team).dying = false);
        for(int x = 0; x < world.width(); x++){
            for(int y = 0; y < world.height(); y++){
                Tile tile = world.tile(x, y);
                if(tile.build != null && tile.team() == team){
                    Time.run(Mathf.random(60f * 6), tile.build::kill);
                }
            }
        }
       data.data(team).dying = false;
       data.data(team).chosen = false;
    }

    void loadout(Player player, int x, int y){
        Stile coreTile = start.tiles.find(s -> s.block instanceof CoreBlock);
        if(coreTile == null) throw new IllegalArgumentException("Schematic has no core tile. Exiting.");
        int ox = x - coreTile.x, oy = y - coreTile.y;
        start.tiles.each(st -> {
            Tile tile = world.tile(st.x + ox, st.y + oy);
            if(tile == null) return;

            if(tile.block() != Blocks.air){
                tile.removeNet();
            }

            tile.setNet(st.block, player.team(), st.rotation);

            if(st.config != null){
                tile.build.configureAny(st.config);
            }
            if(tile.block() instanceof CoreBlock){
                for(ItemStack stack : state.rules.loadout){
                    Call.setItem(tile.build, stack.item, stack.amount);
                }
            }
        });
    }

    public boolean active(){
        return state.rules.tags.getBool("hexed") && !state.is(State.menu);
    }


}
