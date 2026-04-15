package com.nosfabrica.graperank.db;

import java.util.List;

public interface IGraphDB {
    List<String> getUsersConnectedToObserver(String observer, Integer hopsLimit);

    List<RelationshipInfo> getIncomingFollowRelationshipsBulk(List<String> pubkeys);

    List<RelationshipInfo> getIncomingReportRelationshipsBulk(List<String> pubkeys);

    List<RelationshipInfo> getOutgoingRelationshipsBulk(List<String> pubkeys);
}