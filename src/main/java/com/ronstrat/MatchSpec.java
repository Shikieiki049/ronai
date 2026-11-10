package com.ronstrat;

import java.util.ArrayList;
import java.util.List;

/**
 * "expert vs normal*5"            -> 1 expert against 5 allied normals
 * "expert vs hard*4 ffa"          -> everyone for themselves
 * "hard,hard vs normal*3"         -> 2v3 teams
 */
public final class MatchSpec {
    public final List<List<Difficulty>> teams = new ArrayList<>();
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
        if (t.endsWith(" readied")) { m.readied = true; t = t.substring(0, t.length() - 8); }
        if (t.endsWith(" nohandicap")) { m.handicap = false; t = t.substring(0, t.length() - 11); }
        if (t.endsWith(" ffa")) { m.ffa = true; t = t.substring(0, t.length() - 4); }
        if (t.endsWith(" readied")) { m.readied = true; t = t.substring(0, t.length() - 8); }
        for (String side : t.split("\\s+vs\\s+")) {
            List<Difficulty> team = new ArrayList<>();
            for (String part : side.split("[,+]")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                int n = 1;
                int star = part.indexOf('*');
                if (star >= 0) { n = Integer.parseInt(part.substring(star + 1).trim()); part = part.substring(0, star); }
                Difficulty d = Difficulty.parse(part);
                for (int i = 0; i < n; i++) team.add(d);
            }
            if (!team.isEmpty()) m.teams.add(team);
        }
        if (m.teams.size() < 2 && !(m.ffa && !m.teams.isEmpty() && m.teams.get(0).size() > 1))
            throw new IllegalArgumentException("need at least two sides, e.g. 'expert vs normal*5'");
        return m;
    }

    public int players() {
        int n = 0;
        for (List<Difficulty> t : teams) n += t.size();
        return n;
    }
}
