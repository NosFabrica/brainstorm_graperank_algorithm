package com.nosfabrica.graperank.db;

import java.util.List;
import java.util.Map;

public interface IGraphDB {
    List<String> getUsersConnectedToObserver(String observer, Integer hopsLimit);

    List<RelationshipInfo> getIncomingFollowRelationshipsBulk(List<String> pubkeys);

    List<RelationshipInfo> getIncomingReportRelationshipsBulk(List<String> pubkeys);

    List<RelationshipInfo> getOutgoingRelationshipsBulk(List<String> pubkeys);

    Map<String, Double> getUsersConnectedToObserverWithPreviousInfluence(String observer);
}
