package com.ronstrat;

import java.util.ArrayList;
import java.util.List;

/**
 * "expert vs normal*5"            -> 1 expert against 5 allied normals
 * "expert vs hard*4 ffa"          -> everyone for themselves
 * "hard,hard vs normal*3"         -> 2v3 teams
 *
 * A player is difficulty[@brain][:profile][:hN][*count]:
 * "expert vs expert@base"         -> the current expert against the frozen baseline expert
 * "expert:rush7"                  -> expert with the parameter profile rush7
 * "expert:h4"                     -> expert with four handicap levels (as set in the lobby)
 */
public final class MatchSpec {
    /** brain "" = current code, profile "" = plain difficulty, handicap = lobby handicap levels */
    public record Player(Difficulty difficulty, String brain, String profile, int handicap) {
        /** the part of the bot's name after the team number: expert, expert@base, expert:rush7 */
        public String label() {
            return difficulty.key() + (brain.isEmpty() ? "" : "@" + brain) + (profile.isEmpty() ? "" : ":" + profile);
        }
    }

    public final List<List<Player>> teams = new ArrayList<>();
    public boolean ffa;
    /** 1vN handicap for the lone side; on by default, "nohandicap" turns it off */
    public boolean handicap = true;
    /** "readied": every player starts the way RON starts a seat from its lobby (town centre placed, 150/150/50) */
    public boolean readied;
    public final String text;

    private MatchSpec(String text) { this.text = text; }

    public static MatchSpec parse(String s) {
        MatchSpec m = new MatchSpec(s.trim());
        String t = s.trim().toLowerCase();
        for (boolean again = true; again; ) {
            again = false;
            if (t.endsWith(" readied")) { m.readied = true; t = t.substring(0, t.length() - 8); again = true; }
            if (t.endsWith(" nohandicap")) { m.handicap = false; t = t.substring(0, t.length() - 11); again = true; }
            if (t.endsWith(" ffa")) { m.ffa = true; t = t.substring(0, t.length() - 4); again = true; }
        }
        for (String side : t.split("\\s+vs\\s+")) {
            List<Player> team = new ArrayList<>();
            for (String part : side.split("[,+]")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                int n = 1;
                int star = part.indexOf('*');
                if (star >= 0) { n = Integer.parseInt(part.substring(star + 1).trim()); part = part.substring(0, star); }
                Player p = player(part.trim());
                for (int i = 0; i < n; i++) team.add(p);
            }
            if (!team.isEmpty()) m.teams.add(team);
        }
        if (m.teams.size() < 2 && !(m.ffa && !m.teams.isEmpty() && m.teams.get(0).size() > 1))
            throw new IllegalArgumentException("need at least two sides, e.g. 'expert vs normal*5'");
        return m;
    }

    private static Player player(String token) {
        String[] f = token.split(":");
        String head = f[0], brain = "", profile = "";
        int handicap = 0;
        int at = head.indexOf('@');
        if (at >= 0) { brain = head.substring(at + 1).trim(); head = head.substring(0, at); }
        for (int i = 1; i < f.length; i++) {
            String x = f[i].trim();
            if (x.matches("h\\d+")) handicap = Integer.parseInt(x.substring(1));
            else if (!x.isEmpty()) profile = x;
        }
        return new Player(Difficulty.parse(head), brain, profile, handicap);
    }

    public int players() {
        int n = 0;
        for (List<Player> t : teams) n += t.size();
        return n;
    }
}
