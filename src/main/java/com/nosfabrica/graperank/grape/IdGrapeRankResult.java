package com.nosfabrica.graperank.grape;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nosfabrica.graperank.db.RunIds;
import com.nosfabrica.graperank.rank.ScoreCard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A run's result as it is sent once scores are in Postgres: users named by User id. The
 * Observer's pubkey is carried once; each Scorecard is keyed by the Observee's id and repeats
 * neither. The server turns it back into [GrapeRankResult]'s shape when it reads the message.
 *
 * Its field names differ from [GrapeRankResult]'s on purpose (`scorecardsById`, `…Ids`): the
 * server tells the two apart by them, and an id is never read as a pubkey.
 */
public class IdGrapeRankResult extends GrapeRankResult {

    private final String observer;
    private final Map<Integer, ScoreCard> scorecardsById;
    private final List<Integer> changedScoreIds;
    private final List<Integer> droppedBelowCutoffIds;
    private final List<Integer> neo4jWriteIds;
    private final List<Integer> neo4jClearIds;

    /** Empties [result]'s pubkey fields into id fields. A scored user without an id fails the run. */
    public IdGrapeRankResult(String observer, GrapeRankResult result, RunIds ids) {
        super(null, result.getRounds(), result.getDurationSeconds(), result.isSuccess(), result.getError());
        setNeo4jPersistFloor(result.getNeo4jPersistFloor());
        setChangedScorePubkeys(null);
        setDroppedBelowCutoffPubkeys(null);
        setNeo4jClearPubkeys(null);
        this.observer = observer;

        Map<String, ScoreCard> cards = result.getScorecards();
        this.scorecardsById = new LinkedHashMap<>(cards.size() * 2);
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, ScoreCard> entry : cards.entrySet()) {
            Integer id = ids.idOf(entry.getKey());
            if (id == null) {
                missing.add(entry.getKey());
                continue;
            }
            ScoreCard card = entry.getValue();
            card.setObserver(null);
            card.setObservee(null);
            card.setContext(null);
            scorecardsById.put(id, card);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(missing.size() + " scored users have no User id, e.g. "
                    + missing.subList(0, Math.min(3, missing.size())));
        }
        this.changedScoreIds = idsOf(result.getChangedScorePubkeys(), ids);
        this.droppedBelowCutoffIds = idsOf(result.getDroppedBelowCutoffPubkeys(), ids);
        this.neo4jWriteIds = idsOf(result.getNeo4jWritePubkeys(), ids);
        this.neo4jClearIds = idsOf(result.getNeo4jClearPubkeys(), ids);
    }

    /** Null stays null (not computed). A cleared or dropped user the run no longer scores is
     * looked up; one without an id has nothing stored and is left out. */
    private static List<Integer> idsOf(List<String> pubkeys, RunIds ids) {
        if (pubkeys == null) return null;
        ids.resolvePubkeys(pubkeys);
        List<Integer> out = new ArrayList<>(pubkeys.size());
        for (String pubkey : pubkeys) {
            Integer id = ids.idOf(pubkey);
            if (id != null) out.add(id);
        }
        return out;
    }

    public String getObserver() {
        return observer;
    }

    @JsonProperty("scorecardsById")
    public Map<Integer, ScoreCard> getScorecardsById() {
        return scorecardsById;
    }

    public List<Integer> getChangedScoreIds() {
        return changedScoreIds;
    }

    public List<Integer> getDroppedBelowCutoffIds() {
        return droppedBelowCutoffIds;
    }

    @JsonProperty("neo4jWriteIds")
    public List<Integer> getNeo4jWriteIds() {
        return neo4jWriteIds;
    }

    @JsonProperty("neo4jClearIds")
    public List<Integer> getNeo4jClearIds() {
        return neo4jClearIds;
    }
}
