package com.nosfabrica.graperank.db;

import java.util.List;
import java.util.Map;

public interface IGraphDB {
    List<String> getUsersConnectedToObserver(String observer, Integer hopsLimit);

    Map<String, Double> getUsersConnectedToObserverWithPreviousInfluence(String observer);
}
