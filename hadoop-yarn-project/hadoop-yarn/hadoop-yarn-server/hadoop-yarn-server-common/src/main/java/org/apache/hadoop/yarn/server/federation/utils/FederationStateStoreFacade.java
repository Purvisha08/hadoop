/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.federation.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.Random;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.FactoryBuilder;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheLoaderException;
import javax.cache.spi.CachingProvider;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.retry.RetryPolicies;
import org.apache.hadoop.io.retry.RetryPolicy;
import org.apache.hadoop.io.retry.RetryProxy;
import org.apache.hadoop.security.token.delegation.DelegationKey;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ReservationId;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.server.federation.policies.FederationPolicyUtils;
import org.apache.hadoop.yarn.server.federation.policies.exceptions.FederationPolicyException;
import org.apache.hadoop.yarn.server.federation.resolver.SubClusterResolver;
import org.apache.hadoop.yarn.server.federation.store.FederationStateStore;
import org.apache.hadoop.yarn.server.federation.store.exception.FederationStateStoreRetriableException;
import org.apache.hadoop.yarn.server.federation.store.records.AddApplicationHomeSubClusterRequest;
import org.apache.hadoop.yarn.server.federation.store.records.AddApplicationHomeSubClusterResponse;
import org.apache.hadoop.yarn.server.federation.store.records.AddReservationHomeSubClusterRequest;
import org.apache.hadoop.yarn.server.federation.store.records.AddReservationHomeSubClusterResponse;
import org.apache.hadoop.yarn.server.federation.store.records.ApplicationHomeSubCluster;
import org.apache.hadoop.yarn.server.federation.store.records.GetApplicationHomeSubClusterRequest;
import org.apache.hadoop.yarn.server.federation.store.records.GetApplicationHomeSubClusterResponse;
import org.apache.hadoop.yarn.server.federation.store.records.GetReservationHomeSubClusterRequest;
import org.apache.hadoop.yarn.server.federation.store.records.GetReservationHomeSubClusterResponse;
import org.apache.hadoop.yarn.server.federation.store.records.GetSubClusterInfoRequest;
import org.apache.hadoop.yarn.server.federation.store.records.GetSubClusterInfoResponse;
import org.apache.hadoop.yarn.server.federation.store.records.GetSubClusterPoliciesConfigurationsRequest;
import org.apache.hadoop.yarn.server.federation.store.records.GetSubClusterPoliciesConfigurationsResponse;
import org.apache.hadoop.yarn.server.federation.store.records.GetSubClusterPolicyConfigurationRequest;
import org.apache.hadoop.yarn.server.federation.store.records.GetSubClusterPolicyConfigurationResponse;
import org.apache.hadoop.yarn.server.federation.store.records.GetSubClustersInfoRequest;
import org.apache.hadoop.yarn.server.federation.store.records.GetSubClustersInfoResponse;
import org.apache.hadoop.yarn.server.federation.store.records.ReservationHomeSubCluster;
import org.apache.hadoop.yarn.server.federation.store.records.SubClusterId;
import org.apache.hadoop.yarn.server.federation.store.records.SubClusterInfo;
import org.apache.hadoop.yarn.server.federation.store.records.SubClusterPolicyConfiguration;
import org.apache.hadoop.yarn.server.federation.store.records.UpdateApplicationHomeSubClusterRequest;
import org.apache.hadoop.yarn.server.federation.store.records.UpdateReservationHomeSubClusterRequest;
import org.apache.hadoop.yarn.server.federation.store.records.DeleteReservationHomeSubClusterRequest;
import org.apache.hadoop.yarn.server.federation.store.records.RouterMasterKeyRequest;
import org.apache.hadoop.yarn.server.federation.store.records.RouterMasterKeyResponse;
import org.apache.hadoop.yarn.server.federation.store.records.RouterMasterKey;
import org.apache.hadoop.yarn.server.federation.store.records.RouterStoreToken;
import org.apache.hadoop.yarn.server.federation.store.records.RouterRMTokenRequest;
import org.apache.hadoop.yarn.server.federation.store.records.RouterRMTokenResponse;
import org.apache.hadoop.yarn.server.federation.store.records.SubClusterState;
import org.apache.hadoop.yarn.server.federation.store.records.SubClusterDeregisterRequest;
import org.apache.hadoop.yarn.server.federation.store.records.SubClusterDeregisterResponse;
import org.apache.hadoop.yarn.security.client.RMDelegationTokenIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.classification.VisibleForTesting;
import com.zaxxer.hikari.pool.HikariPool.PoolInitializationException;

/**
 *
 * The FederationStateStoreFacade is an utility wrapper that provides singleton
 * access to the Federation state store. It abstracts out retries and in
 * addition, it also implements the caching for various objects.
 *
 */
public final class FederationStateStoreFacade {
  private static final Logger LOG =
      LoggerFactory.getLogger(FederationStateStoreFacade.class);

  private static final String GET_SUBCLUSTERS_CACHEID = "getSubClusters";
  private static final String GET_POLICIES_CONFIGURATIONS_CACHEID =
      "getPoliciesConfigurations";
  private static final String GET_APPLICATION_HOME_SUBCLUSTER_CACHEID =
      "getApplicationHomeSubCluster";

  private static final FederationStateStoreFacade FACADE =
      new FederationStateStoreFacade();

  private static Random rand = new Random(System.currentTimeMillis());

  private FederationStateStore stateStore;
  private int cacheTimeToLive;
  private Configuration conf;
  private Cache<Object, Object> cache;
  private SubClusterResolver subclusterResolver;

  private FederationStateStoreFacade() {
    initializeFacadeInternal(new Configuration());
  }

  private void initializeFacadeInternal(Configuration config) {
    this.conf = config;
    try {
      this.stateStore = (FederationStateStore) createRetryInstance(this.conf,
          YarnConfiguration.FEDERATION_STATESTORE_CLIENT_CLASS,
          YarnConfiguration.DEFAULT_FEDERATION_STATESTORE_CLIENT_CLASS,
          FederationStateStore.class, createRetryPolicy(conf));
      this.stateStore.init(conf);

      this.subclusterResolver = createInstance(conf,
          YarnConfiguration.FEDERATION_CLUSTER_RESOLVER_CLASS,
          YarnConfiguration.DEFAULT_FEDERATION_CLUSTER_RESOLVER_CLASS,
          SubClusterResolver.class);
      this.subclusterResolver.load();

      initCache();

    } catch (YarnException ex) {
      LOG.error("Failed to initialize the FederationStateStoreFacade object",
          ex);
      throw new RuntimeException(ex);
    }
  }

  /**
   * Delete and re-initialize the cache, to force it to use the given
   * configuration.
   *
   * @param store the {@link FederationStateStore} instance to reinitialize with
   * @param config the updated configuration to reinitialize with
   */
  @VisibleForTesting
  public synchronized void reinitialize(FederationStateStore store,
      Configuration config) {
    this.conf = config;
    this.stateStore = store;
    clearCache();
    initCache();
  }

  /**
   * Create a RetryPolicy for {@code FederationStateStoreFacade}. In case of
   * failure, it retries for:
   * <ul>
   * <li>{@code FederationStateStoreRetriableException}</li>
   * <li>{@code CacheLoaderException}</li>
   * </ul>
   *
   * @param conf the updated configuration
   * @return the RetryPolicy for FederationStateStoreFacade
   */
  public static RetryPolicy createRetryPolicy(Configuration conf) {
    // Retry settings for StateStore
    RetryPolicy basePolicy = RetryPolicies.exponentialBackoffRetry(
        conf.getInt(YarnConfiguration.CLIENT_FAILOVER_RETRIES, Integer.SIZE),
        conf.getLong(YarnConfiguration.CLIENT_FAILOVER_SLEEPTIME_BASE_MS,
            YarnConfiguration.DEFAULT_RESOURCEMANAGER_CONNECT_RETRY_INTERVAL_MS),
        TimeUnit.MILLISECONDS);
    Map<Class<? extends Exception>, RetryPolicy> exceptionToPolicyMap =
        new HashMap<Class<? extends Exception>, RetryPolicy>();
    exceptionToPolicyMap.put(FederationStateStoreRetriableException.class,
        basePolicy);
    exceptionToPolicyMap.put(CacheLoaderException.class, basePolicy);
    exceptionToPolicyMap.put(PoolInitializationException.class, basePolicy);

    RetryPolicy retryPolicy = RetryPolicies.retryByException(
        RetryPolicies.TRY_ONCE_THEN_FAIL, exceptionToPolicyMap);
    return retryPolicy;
  }

  private boolean isCachingEnabled() {
    return (cacheTimeToLive > 0);
  }

  private void initCache() {
    // Picking the JCache provider from classpath, need to make sure there's
    // no conflict or pick up a specific one in the future
    cacheTimeToLive =
        conf.getInt(YarnConfiguration.FEDERATION_CACHE_TIME_TO_LIVE_SECS,
            YarnConfiguration.DEFAULT_FEDERATION_CACHE_TIME_TO_LIVE_SECS);
    if (isCachingEnabled()) {
      CachingProvider jcacheProvider = Caching.getCachingProvider();
      CacheManager jcacheManager = jcacheProvider.getCacheManager();
      this.cache = jcacheManager.getCache(this.getClass().getSimpleName());
      if (this.cache == null) {
        LOG.info("Creating a JCache Manager with name "
            + this.getClass().getSimpleName());
        Duration cacheExpiry = new Duration(TimeUnit.SECONDS, cacheTimeToLive);
        CompleteConfiguration<Object, Object> configuration =
            new MutableConfiguration<Object, Object>().setStoreByValue(false)
                .setReadThrough(true)
                .setExpiryPolicyFactory(
                    new FactoryBuilder.SingletonFactory<ExpiryPolicy>(
                        new CreatedExpiryPolicy(cacheExpiry)))
                .setCacheLoaderFactory(
                    new FactoryBuilder.SingletonFactory<CacheLoader<Object, Object>>(
                        new CacheLoaderImpl<Object, Object>()));
        this.cache = jcacheManager.createCache(this.getClass().getSimpleName(),
            configuration);
      }
    }
  }

  private void clearCache() {
    CachingProvider jcacheProvider = Caching.getCachingProvider();
    CacheManager jcacheManager = jcacheProvider.getCacheManager();

    jcacheManager.destroyCache(this.getClass().getSimpleName());
    this.cache = null;
  }

  /**
   * Returns the singleton instance of the FederationStateStoreFacade object.
   *
   * @return the singleton {@link FederationStateStoreFacade} instance
   */
  public static FederationStateStoreFacade getInstance() {
    return FACADE;
  }

  /**
   * Returns the {@link SubClusterInfo} for the specified {@link SubClusterId}.
   *
   * @param subClusterId the identifier of the sub-cluster
   * @return the sub cluster information, or
   *         {@code null} if there is no mapping for the subClusterId
   * @throws YarnException if the call to the state store is unsuccessful
   */
  public SubClusterInfo getSubCluster(final SubClusterId subClusterId)
      throws YarnException {
    if (isCachingEnabled()) {
      return getSubClusters(false).get(subClusterId);
    } else {
      GetSubClusterInfoResponse response = stateStore
          .getSubCluster(GetSubClusterInfoRequest.newInstance(subClusterId));
      if (response == null) {
        return null;
      } else {
        return response.getSubClusterInfo();
      }
    }
  }

  /**
   * Updates the cache with the central {@link FederationStateStore} and returns
   * the {@link SubClusterInfo} for the specified {@link SubClusterId}.
   *
   * @param subClusterId the identifier of the sub-cluster
   * @param flushCache flag to indicate if the cache should be flushed or not
   * @return the sub cluster information
   * @throws YarnException if the call to the state store is unsuccessful
   */
  public SubClusterInfo getSubCluster(final SubClusterId subClusterId,
      final boolean flushCache) throws YarnException {
    if (flushCache && isCachingEnabled()) {
      LOG.info("Flushing subClusters from cache and rehydrating from store,"
          + " most likely on account of RM failover.");
      cache.remove(buildGetSubClustersCacheRequest(false));
    }
    return getSubCluster(subClusterId);
  }

  /**
   * Returns the {@link SubClusterInfo} of all active sub cluster(s).
   *
   * @param filterInactiveSubClusters whether to filter out inactive
   *          sub-clusters
   * @return the information of all active sub cluster(s)
   * @throws YarnException if the call to the state store is unsuccessful
   */
  @SuppressWarnings("unchecked")
  public Map<SubClusterId, SubClusterInfo> getSubClusters(
      final boolean filterInactiveSubClusters) throws YarnException {
    try {
      if (isCachingEnabled()) {
        return (Map<SubClusterId, SubClusterInfo>) cache
            .get(buildGetSubClustersCacheRequest(filterInactiveSubClusters));
      } else {
        return buildSubClusterInfoMap(stateStore.getSubClusters(
            GetSubClustersInfoRequest.newInstance(filterInactiveSubClusters)));
      }
    } catch (Throwable ex) {
      throw new YarnException(ex);
    }
  }

  /**
   * Returns the {@link SubClusterPolicyConfiguration} for the specified queue.
   *
   * @param queue the queue whose policy is required
   * @return the corresponding configured policy, or {@code null} if there is no
   *         mapping for the queue
   * @throws YarnException if the call to the state store is unsuccessful
   */
  public SubClusterPolicyConfiguration getPolicyConfiguration(
      final String queue) throws YarnException {
    if (isCachingEnabled()) {
      return getPoliciesConfigurations().get(queue);
    } else {

      GetSubClusterPolicyConfigurationResponse response =
          stateStore.getPolicyConfiguration(
              GetSubClusterPolicyConfigurationRequest.newInstance(queue));
      if (response == null) {
        return null;
      } else {
        return response.getPolicyConfiguration();
      }
    }
  }

  /**
   * Get the policies that is represented as
   * {@link SubClusterPolicyConfiguration} for all currently active queues in
   * the system.
   *
   * @return the policies for all currently active queues in the system
   * @throws YarnException if the call to the state store is unsuccessful
   */
  @SuppressWarnings("unchecked")
  public Map<String, SubClusterPolicyConfiguration> getPoliciesConfigurations()
      throws YarnException {
    try {
      if (isCachingEnabled()) {
        return (Map<String, SubClusterPolicyConfiguration>) cache
            .get(buildGetPoliciesConfigurationsCacheRequest());
      } else {
        return buildPolicyConfigMap(stateStore.getPoliciesConfigurations(
            GetSubClusterPoliciesConfigurationsRequest.newInstance()));
      }
    } catch (Throwable ex) {
      throw new YarnException(ex);
    }
  }

  /**
   * Adds the home {@link SubClusterId} for the specified {@link ApplicationId}.
   *
   * @param appHomeSubCluster the mapping of the application to it's home
   *          sub-cluster
   * @return the stored Subcluster from StateStore
   * @throws YarnException if the call to the state store is unsuccessful
   */
  public SubClusterId addApplicationHomeSubCluster(
      ApplicationHomeSubCluster appHomeSubCluster) throws YarnException {
    AddApplicationHomeSubClusterResponse response =
        stateStore.addApplicationHomeSubCluster(
            AddApplicationHomeSubClusterRequest.newInstance(appHomeSubCluster));
    return response.getHomeSubCluster();
  }

  /**
   * Updates the home {@link SubClusterId} for the specified
   * {@link ApplicationId}.
   *
   * @param appHomeSubCluster the mapping of the application to it's home
   *          sub-cluster
   * @throws YarnException if the call to the state store is unsuccessful
   */
  public void updateApplicationHomeSubCluster(
      ApplicationHomeSubCluster appHomeSubCluster) throws YarnException {
    stateStore.updateApplicationHomeSubCluster(
        UpdateApplicationHomeSubClusterRequest.newInstance(appHomeSubCluster));
    return;
  }

  /**
   * Returns the home {@link SubClusterId} for the specified
   * {@link ApplicationId}.
   *
   * @param appId the identifier of the application
   * @return the home sub cluster identifier
   * @throws YarnException if the call to the state store is unsuccessful
   */
  public SubClusterId getApplicationHomeSubCluster(ApplicationId appId)
      throws YarnException {
    try {
      if (isCachingEnabled()) {
        SubClusterId value = SubClusterId.class.cast(
            cache.get(buildGetApplicationHomeSubClusterRequest(appId)));
        return value;
      } else {
        GetApplicationHomeSubClusterResponse response = stateStore.getApplicationHomeSubCluster(
            GetApplicationHomeSubClusterRequest.newInstance(appId));
        return response.getApplicationHomeSubCluster().getHomeSubCluster();
      }
    } catch (Throwable ex) {
      throw new YarnException(ex);
    }
  }

  /**
   * Get the singleton instance of SubClusterResolver.
   *
   * @return SubClusterResolver instance
   */
  public SubClusterResolver getSubClusterResolver() {
    return this.subclusterResolver;
  }

  /**
   * Get the configuration.
   *
   * @return configuration object
   */
  public Configuration getConf() {
    return this.conf;
  }

  /**
   * Adds the home {@link SubClusterId} for the specified {@link ReservationId}.
   *
   * @param appHomeSubCluster the mapping of the reservation to it's home
   *          sub-cluster
   * @return the stored subCluster from StateStore
   * @throws YarnException if the call to the state store is unsuccessful
   */
  public SubClusterId addReservationHomeSubCluster(ReservationHomeSubCluster appHomeSubCluster)
      throws YarnException {
    AddReservationHomeSubClusterResponse response = stateStore.addReservationHomeSubCluster(
        AddReservationHomeSubClusterRequest.newInstance(appHomeSubCluster));
    return response.getHomeSubCluster();
  }

  /**
   * Returns the home {@link SubClusterId} for the specified {@link ReservationId}.
   *
   * @param reservationId the identifier of the reservation
   * @return the home subCluster identifier
   * @throws YarnException if the call to the state store is unsuccessful
   */
  public SubClusterId getReservationHomeSubCluster(ReservationId reservationId)
      throws YarnException {
    GetReservationHomeSubClusterResponse response = stateStore.getReservationHomeSubCluster(
         GetReservationHomeSubClusterRequest.newInstance(reservationId));
    return response.getReservationHomeSubCluster().getHomeSubCluster();
  }

  /**
   * Updates the home {@link SubClusterId} for the specified
   * {@link ReservationId}.
   *
   * @param appHomeSubCluster the mapping of the reservation to it's home
   *          sub-cluster
   * @throws YarnException if the call to the state store is unsuccessful
   */
  public void updateReservationHomeSubCluster(ReservationHomeSubCluster appHomeSubCluster)
      throws YarnException {
    UpdateReservationHomeSubClusterRequest request =
        UpdateReservationHomeSubClusterRequest.newInstance(appHomeSubCluster);
    stateStore.updateReservationHomeSubCluster(request);
  }

  /**
   * Delete the home {@link SubClusterId} for the specified
   * {@link ReservationId}.
   *
   * @param reservationId the identifier of the reservation
   * @throws YarnException if the call to the state store is unsuccessful
   */
  public void deleteReservationHomeSubCluster(ReservationId reservationId) throws YarnException {
    DeleteReservationHomeSubClusterRequest request =
        DeleteReservationHomeSubClusterRequest.newInstance(reservationId);
    stateStore.deleteReservationHomeSubCluster(request);
  }

  /**
   * Helper method to create instances of Object using the class name defined in
   * the configuration object. The instances creates {@link RetryProxy} using
   * the specific {@link RetryPolicy}.
   *
   * @param conf the yarn configuration
   * @param configuredClassName the configuration provider key
   * @param defaultValue the default implementation for fallback
   * @param type the class for which a retry proxy is required
   * @param retryPolicy the policy for retrying method call failures
   * @param <T> The type of the instance.
   * @return a retry proxy for the specified interface
   */
  public static <T> Object createRetryInstance(Configuration conf,
      String configuredClassName, String defaultValue, Class<T> type,
      RetryPolicy retryPolicy) {

    return RetryProxy.create(type,
        createInstance(conf, configuredClassName, defaultValue, type),
        retryPolicy);
  }

  /**
   * Helper method to create instances of Object using the class name specified
   * in the configuration object.
   *
   * @param conf the yarn configuration
   * @param configuredClassName the configuration provider key
   * @param defaultValue the default implementation class
   * @param type the required interface/base class
   * @param <T> The type of the instance to create
   * @return the instances created
   */
  @SuppressWarnings("unchecked")
  public static <T> T createInstance(Configuration conf,
      String configuredClassName, String defaultValue, Class<T> type) {

    String className = conf.get(configuredClassName, defaultValue);
    try {
      Class<?> clusterResolverClass = conf.getClassByName(className);
      if (type.isAssignableFrom(clusterResolverClass)) {
        return (T) ReflectionUtils.newInstance(clusterResolverClass, conf);
      } else {
        throw new YarnRuntimeException("Class: " + className
            + " not instance of " + type.getCanonicalName());
      }
    } catch (ClassNotFoundException e) {
      throw new YarnRuntimeException("Could not instantiate : " + className, e);
    }
  }

  private Map<SubClusterId, SubClusterInfo> buildSubClusterInfoMap(
      final GetSubClustersInfoResponse response) {
    List<SubClusterInfo> subClusters = response.getSubClusters();
    Map<SubClusterId, SubClusterInfo> subClustersMap =
        new HashMap<>(subClusters.size());
    for (SubClusterInfo subCluster : subClusters) {
      subClustersMap.put(subCluster.getSubClusterId(), subCluster);
    }
    return subClustersMap;
  }

  private Object buildGetSubClustersCacheRequest(
      final boolean filterInactiveSubClusters) {
    final String cacheKey =
        buildCacheKey(getClass().getSimpleName(), GET_SUBCLUSTERS_CACHEID,
            Boolean.toString(filterInactiveSubClusters));
    CacheRequest<String, Map<SubClusterId, SubClusterInfo>> cacheRequest =
        new CacheRequest<String, Map<SubClusterId, SubClusterInfo>>(cacheKey,
            new Func<String, Map<SubClusterId, SubClusterInfo>>() {
              @Override
              public Map<SubClusterId, SubClusterInfo> invoke(String key)
                  throws Exception {
                GetSubClustersInfoResponse subClusters =
                    stateStore.getSubClusters(GetSubClustersInfoRequest
                        .newInstance(filterInactiveSubClusters));
                return buildSubClusterInfoMap(subClusters);
              }
            });
    return cacheRequest;
  }

  private Map<String, SubClusterPolicyConfiguration> buildPolicyConfigMap(
      GetSubClusterPoliciesConfigurationsResponse response) {
    List<SubClusterPolicyConfiguration> policyConfigs =
        response.getPoliciesConfigs();
    Map<String, SubClusterPolicyConfiguration> queuePolicyConfigs =
        new HashMap<>();
    for (SubClusterPolicyConfiguration policyConfig : policyConfigs) {
      queuePolicyConfigs.put(policyConfig.getQueue(), policyConfig);
    }
    return queuePolicyConfigs;
  }

  private Object buildGetPoliciesConfigurationsCacheRequest() {
    final String cacheKey = buildCacheKey(getClass().getSimpleName(),
        GET_POLICIES_CONFIGURATIONS_CACHEID, null);
    CacheRequest<String, Map<String, SubClusterPolicyConfiguration>> cacheRequest =
        new CacheRequest<String, Map<String, SubClusterPolicyConfiguration>>(
            cacheKey,
            new Func<String, Map<String, SubClusterPolicyConfiguration>>() {
              @Override
              public Map<String, SubClusterPolicyConfiguration> invoke(
                  String key) throws Exception {
                GetSubClusterPoliciesConfigurationsResponse policyConfigs =
                    stateStore.getPoliciesConfigurations(
                        GetSubClusterPoliciesConfigurationsRequest
                            .newInstance());
                return buildPolicyConfigMap(policyConfigs);
              }
            });
    return cacheRequest;
  }

  private Object buildGetApplicationHomeSubClusterRequest(ApplicationId applicationId) {
    final String cacheKey = buildCacheKey(getClass().getSimpleName(),
        GET_APPLICATION_HOME_SUBCLUSTER_CACHEID, applicationId.toString());
    CacheRequest<String, SubClusterId> cacheRequest = new CacheRequest<>(
        cacheKey,
        input -> {

          GetApplicationHomeSubClusterRequest request =
              GetApplicationHomeSubClusterRequest.newInstance(applicationId);
          GetApplicationHomeSubClusterResponse response =
              stateStore.getApplicationHomeSubCluster(request);

          ApplicationHomeSubCluster appHomeSubCluster = response.getApplicationHomeSubCluster();
          SubClusterId subClusterId = appHomeSubCluster.getHomeSubCluster();

          return subClusterId;
        });
    return cacheRequest;
  }

  protected String buildCacheKey(String typeName, String methodName,
      String argName) {
    StringBuilder buffer = new StringBuilder();
    buffer.append(typeName).append(".")
        .append(methodName);
    if (argName != null) {
      buffer.append("::");
      buffer.append(argName);
    }
    return buffer.toString();
  }

  /**
   * Internal class that implements the CacheLoader interface that can be
   * plugged into the CacheManager to load objects into the cache for specified
   * keys.
   */
  private static class CacheLoaderImpl<K, V> implements CacheLoader<K, V> {
    @SuppressWarnings("unchecked")
    @Override
    public V load(K key) throws CacheLoaderException {
      try {
        CacheRequest<K, V> query = (CacheRequest<K, V>) key;
        assert query != null;
        return query.getValue();
      } catch (Throwable ex) {
        throw new CacheLoaderException(ex);
      }
    }

    @Override
    public Map<K, V> loadAll(Iterable<? extends K> keys)
        throws CacheLoaderException {
      // The FACADE does not use the Cache's getAll API. Hence this is not
      // required to be implemented
      throw new NotImplementedException("Code is not implemented");
    }
  }

  /**
   * Internal class that encapsulates the cache key and a function that returns
   * the value for the specified key.
   */
  private static class CacheRequest<K, V> {
    private K key;
    private Func<K, V> func;

    CacheRequest(K key, Func<K, V> func) {
      this.key = key;
      this.func = func;
    }

    public V getValue() throws Exception {
      return func.invoke(key);
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((key == null) ? 0 : key.hashCode());
      return result;
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null) {
        return false;
      }
      if (getClass() != obj.getClass()) {
        return false;
      }
      CacheRequest<K, V> other = (CacheRequest<K, V>) obj;
      if (key == null) {
        if (other.key != null) {
          return false;
        }
      } else if (!key.equals(other.key)) {
        return false;
      }

      return true;
    }
  }

  /**
   * Encapsulates a method that has one parameter and returns a value of the
   * type specified by the TResult parameter.
   */
  protected interface Func<T, TResult> {
    TResult invoke(T input) throws Exception;
  }

  @VisibleForTesting
  public Cache<Object, Object> getCache() {
    return cache;
  }

  @VisibleForTesting
  protected Object getAppHomeSubClusterCacheRequest(ApplicationId applicationId) {
    return buildGetApplicationHomeSubClusterRequest(applicationId);
  }

  @VisibleForTesting
  public FederationStateStore getStateStore() {
    return stateStore;
  }

  /**
   * The Router Supports Store NewMasterKey (RouterMasterKey{@link RouterMasterKey}).
   *
   * @param newKey Key used for generating and verifying delegation tokens
   * @throws YarnException if the call to the state store is unsuccessful
   * @throws IOException An IO Error occurred
   * @return RouterMasterKeyResponse
   */
  public RouterMasterKeyResponse storeNewMasterKey(DelegationKey newKey)
      throws YarnException, IOException {
    LOG.info("Storing master key with keyID {}.", newKey.getKeyId());
    ByteBuffer keyBytes = ByteBuffer.wrap(newKey.getEncodedKey());
    RouterMasterKey masterKey = RouterMasterKey.newInstance(newKey.getKeyId(),
        keyBytes, newKey.getExpiryDate());
    RouterMasterKeyRequest keyRequest = RouterMasterKeyRequest.newInstance(masterKey);
    return stateStore.storeNewMasterKey(keyRequest);
  }

  /**
   * The Router Supports Remove MasterKey (RouterMasterKey{@link RouterMasterKey}).
   *
   * @param newKey Key used for generating and verifying delegation tokens
   * @throws YarnException if the call to the state store is unsuccessful
   * @throws IOException An IO Error occurred
   */
  public void removeStoredMasterKey(DelegationKey newKey) throws YarnException, IOException {
    LOG.info("Removing master key with keyID {}.", newKey.getKeyId());
    ByteBuffer keyBytes = ByteBuffer.wrap(newKey.getEncodedKey());
    RouterMasterKey masterKey = RouterMasterKey.newInstance(newKey.getKeyId(),
        keyBytes, newKey.getExpiryDate());
    RouterMasterKeyRequest keyRequest = RouterMasterKeyRequest.newInstance(masterKey);
    stateStore.removeStoredMasterKey(keyRequest);
  }

  /**
   * The Router Supports GetMasterKeyByDelegationKey.
   *
   * @param newKey Key used for generating and verifying delegation tokens
   * @throws YarnException if the call to the state store is unsuccessful
   * @throws IOException An IO Error occurred
   * @return RouterMasterKeyResponse
   */
  public RouterMasterKeyResponse getMasterKeyByDelegationKey(DelegationKey newKey)
      throws YarnException, IOException {
    LOG.info("Storing master key with keyID {}.", newKey.getKeyId());
    ByteBuffer keyBytes = ByteBuffer.wrap(newKey.getEncodedKey());
    RouterMasterKey masterKey = RouterMasterKey.newInstance(newKey.getKeyId(),
        keyBytes, newKey.getExpiryDate());
    RouterMasterKeyRequest keyRequest = RouterMasterKeyRequest.newInstance(masterKey);
    return stateStore.getMasterKeyByDelegationKey(keyRequest);
  }

  /**
   * The Router Supports Store RMDelegationTokenIdentifier{@link RMDelegationTokenIdentifier}.
   *
   * @param identifier delegation tokens from the RM
   * @param renewDate renewDate
   * @throws YarnException if the call to the state store is unsuccessful
   * @throws IOException An IO Error occurred
   */
  public void storeNewToken(RMDelegationTokenIdentifier identifier,
      long renewDate) throws YarnException, IOException {
    LOG.info("storing RMDelegation token with sequence number: {}.",
        identifier.getSequenceNumber());
    RouterStoreToken storeToken = RouterStoreToken.newInstance(identifier, renewDate);
    RouterRMTokenRequest request = RouterRMTokenRequest.newInstance(storeToken);
    stateStore.storeNewToken(request);
  }

  /**
   * The Router Supports Store RMDelegationTokenIdentifier{@link RMDelegationTokenIdentifier}.
   *
   * @param identifier delegation tokens from the RM.
   * @param renewDate renewDate.
   * @param tokenInfo tokenInfo.
   * @throws YarnException if the call to the state store is unsuccessful.
   * @throws IOException An IO Error occurred.
   */
  public void storeNewToken(RMDelegationTokenIdentifier identifier,
      long renewDate, String tokenInfo) throws YarnException, IOException {
    LOG.info("storing RMDelegation token with sequence number: {}.",
        identifier.getSequenceNumber());
    RouterStoreToken storeToken = RouterStoreToken.newInstance(identifier, renewDate, tokenInfo);
    RouterRMTokenRequest request = RouterRMTokenRequest.newInstance(storeToken);
    stateStore.storeNewToken(request);
  }

  /**
   * The Router Supports Update RMDelegationTokenIdentifier{@link RMDelegationTokenIdentifier}.
   *
   * @param identifier delegation tokens from the RM
   * @param renewDate renewDate
   * @throws YarnException if the call to the state store is unsuccessful
   * @throws IOException An IO Error occurred
   */
  public void updateStoredToken(RMDelegationTokenIdentifier identifier,
      long renewDate) throws YarnException, IOException {
    LOG.info("updating RMDelegation token with sequence number: {}.",
        identifier.getSequenceNumber());
    RouterStoreToken storeToken = RouterStoreToken.newInstance(identifier, renewDate);
    RouterRMTokenRequest request = RouterRMTokenRequest.newInstance(storeToken);
    stateStore.updateStoredToken(request);
  }

  /**
   * The Router Supports Update RMDelegationTokenIdentifier{@link RMDelegationTokenIdentifier}.
   *
   * @param identifier delegation tokens from the RM
   * @param renewDate renewDate
   * @param tokenInfo tokenInfo.
   * @throws YarnException if the call to the state store is unsuccessful.
   * @throws IOException An IO Error occurred.
   */
  public void updateStoredToken(RMDelegationTokenIdentifier identifier,
      long renewDate, String tokenInfo) throws YarnException, IOException {
    LOG.info("updating RMDelegation token with sequence number: {}.",
        identifier.getSequenceNumber());
    RouterStoreToken storeToken = RouterStoreToken.newInstance(identifier, renewDate, tokenInfo);
    RouterRMTokenRequest request = RouterRMTokenRequest.newInstance(storeToken);
    stateStore.updateStoredToken(request);
  }

  /**
   * The Router Supports Remove RMDelegationTokenIdentifier{@link RMDelegationTokenIdentifier}.
   *
   * @param identifier delegation tokens from the RM
   * @throws YarnException if the call to the state store is unsuccessful
   * @throws IOException An IO Error occurred
   */
  public void removeStoredToken(RMDelegationTokenIdentifier identifier)
      throws YarnException, IOException{
    LOG.info("removing RMDelegation token with sequence number: {}.",
        identifier.getSequenceNumber());
    RouterStoreToken storeToken = RouterStoreToken.newInstance(identifier, 0L);
    RouterRMTokenRequest request = RouterRMTokenRequest.newInstance(storeToken);
    stateStore.removeStoredToken(request);
  }

  /**
   * The Router Supports GetTokenByRouterStoreToken{@link RMDelegationTokenIdentifier}.
   *
   * @param identifier delegation tokens from the RM
   * @return RouterStoreToken
   * @throws YarnException if the call to the state store is unsuccessful
   * @throws IOException An IO Error occurred
   */
  public RouterRMTokenResponse getTokenByRouterStoreToken(RMDelegationTokenIdentifier identifier)
      throws YarnException, IOException {
    LOG.info("get RouterStoreToken token with sequence number: {}.",
        identifier.getSequenceNumber());
    RouterStoreToken storeToken = RouterStoreToken.newInstance(identifier, 0L);
    RouterRMTokenRequest request = RouterRMTokenRequest.newInstance(storeToken);
    return stateStore.getTokenByRouterStoreToken(request);
  }

  /**
   * stateStore provides DelegationTokenSeqNum increase.
   *
   * @return delegationTokenSequenceNumber.
   */
  public int incrementDelegationTokenSeqNum() {
    return stateStore.incrementDelegationTokenSeqNum();
  }

  /**
   * Get SeqNum from stateStore.
   *
   * @return delegationTokenSequenceNumber.
   */
  public int getDelegationTokenSeqNum() {
    return stateStore.getDelegationTokenSeqNum();
  }

  /**
   * Set SeqNum from stateStore.
   *
   * @param seqNum delegationTokenSequenceNumber.
   */
  public void setDelegationTokenSeqNum(int seqNum) {
    stateStore.setDelegationTokenSeqNum(seqNum);
  }

  /**
   * Get CurrentKeyId from stateStore.
   *
   * @return currentKeyId.
   */
  public int getCurrentKeyId() {
    return stateStore.getCurrentKeyId();
  }

  /**
   * stateStore provides CurrentKeyId increase.
   *
   * @return currentKeyId.
   */
  public int incrementCurrentKeyId() {
    return stateStore.incrementCurrentKeyId();
  }

  /**
   * Get the number of active cluster nodes.
   *
   * @return number of active cluster nodes.
   * @throws YarnException if the call to the state store is unsuccessful.
   */
  public int getActiveSubClustersCount() throws YarnException {
    Map<SubClusterId, SubClusterInfo> activeSubClusters = getSubClusters(true);
    if (activeSubClusters == null || activeSubClusters.isEmpty()) {
      return 0;
    } else {
      return activeSubClusters.size();
    }
  }

  /**
   * Randomly pick ActiveSubCluster.
   * During the selection process, we will exclude SubClusters from the blacklist.
   *
   * @param activeSubClusters List of active subClusters.
   * @param blackList blacklist.
   * @return Active SubClusterId.
   * @throws YarnException When there is no Active SubCluster,
   * an exception will be thrown (No active SubCluster available to submit the request.)
   */
  public static SubClusterId getRandomActiveSubCluster(
      Map<SubClusterId, SubClusterInfo> activeSubClusters, List<SubClusterId> blackList)
      throws YarnException {

    // Check if activeSubClusters is empty, if it is empty, we need to throw an exception
    if (MapUtils.isEmpty(activeSubClusters)) {
      throw new FederationPolicyException(
          FederationPolicyUtils.NO_ACTIVE_SUBCLUSTER_AVAILABLE);
    }

    // Change activeSubClusters to List
    List<SubClusterId> subClusterIds = new ArrayList<>(activeSubClusters.keySet());

    // If the blacklist is not empty, we need to remove all the subClusters in the blacklist
    if (CollectionUtils.isNotEmpty(blackList)) {
      subClusterIds.removeAll(blackList);
    }

    // Check there are still active subcluster after removing the blacklist
    if (CollectionUtils.isEmpty(subClusterIds)) {
      throw new FederationPolicyException(
          FederationPolicyUtils.NO_ACTIVE_SUBCLUSTER_AVAILABLE);
    }

    // Randomly choose a SubCluster
    return subClusterIds.get(rand.nextInt(subClusterIds.size()));
  }

  /**
   * Get the number of retries.
   *
   * @param configRetries User-configured number of retries.
   * @return number of retries.
   * @throws YarnException yarn exception.
   */
  public int getRetryNumbers(int configRetries) throws YarnException {
    int activeSubClustersCount = getActiveSubClustersCount();
    int actualRetryNums = Math.min(activeSubClustersCount, configRetries);
    // Normally, we don't set a negative number for the number of retries,
    // but if the user sets a negative number for the number of retries,
    // we will return 0
    if (actualRetryNums < 0) {
      return 0;
    }
    return actualRetryNums;
  }

  /**
   * Query SubClusterId By applicationId.
   *
   * If SubClusterId is not empty, it means it exists and returns true;
   * if SubClusterId is empty, it means it does not exist and returns false.
   *
   * @param applicationId applicationId
   * @return true, SubClusterId exists; false, SubClusterId not exists.
   */
  public boolean existsApplicationHomeSubCluster(ApplicationId applicationId) {
    try {
      SubClusterId subClusterId = getApplicationHomeSubCluster(applicationId);
      if (subClusterId != null) {
        return true;
      }
    } catch (YarnException e) {
      LOG.warn("get homeSubCluster by applicationId = {} error.", applicationId, e);
    }
    return false;
  }

  /**
   * Add ApplicationHomeSubCluster to FederationStateStore.
   *
   * @param applicationId applicationId.
   * @param homeSubCluster homeSubCluster, homeSubCluster selected according to policy.
   * @throws YarnException yarn exception.
   */
  public void addApplicationHomeSubCluster(ApplicationId applicationId,
      ApplicationHomeSubCluster homeSubCluster) throws YarnException {
    try {
      addApplicationHomeSubCluster(homeSubCluster);
    } catch (YarnException e) {
      String msg = String.format(
          "Unable to insert the ApplicationId %s into the FederationStateStore.", applicationId);
      throw new YarnException(msg, e);
    }
  }

  /**
   * Update ApplicationHomeSubCluster to FederationStateStore.
   *
   * @param subClusterId homeSubClusterId
   * @param applicationId applicationId.
   * @param homeSubCluster homeSubCluster, homeSubCluster selected according to policy.
   * @throws YarnException yarn exception.
   */
  public void updateApplicationHomeSubCluster(SubClusterId subClusterId,
      ApplicationId applicationId, ApplicationHomeSubCluster homeSubCluster) throws YarnException {
    try {
      updateApplicationHomeSubCluster(homeSubCluster);
    } catch (YarnException e) {
      SubClusterId subClusterIdInStateStore = getApplicationHomeSubCluster(applicationId);
      if (subClusterId == subClusterIdInStateStore) {
        LOG.info("Application {} already submitted on SubCluster {}.", applicationId, subClusterId);
      } else {
        String msg = String.format(
            "Unable to update the ApplicationId %s into the FederationStateStore.", applicationId);
        throw new YarnException(msg, e);
      }
    }
  }

  /**
   * Add or Update ApplicationHomeSubCluster.
   *
   * @param applicationId applicationId, is the id of the application.
   * @param subClusterId homeSubClusterId, this is selected by strategy.
   * @param retryCount number of retries.
   * @throws YarnException yarn exception.
   */
  public void addOrUpdateApplicationHomeSubCluster(ApplicationId applicationId,
      SubClusterId subClusterId, int retryCount) throws YarnException {
    Boolean exists = existsApplicationHomeSubCluster(applicationId);
    ApplicationHomeSubCluster appHomeSubCluster =
        ApplicationHomeSubCluster.newInstance(applicationId, subClusterId);
    if (!exists || retryCount == 0) {
      // persist the mapping of applicationId and the subClusterId which has
      // been selected as its home.
      addApplicationHomeSubCluster(applicationId, appHomeSubCluster);
    } else {
      // update the mapping of applicationId and the home subClusterId to
      // the new subClusterId we have selected.
      updateApplicationHomeSubCluster(subClusterId, applicationId, appHomeSubCluster);
    }
  }

  /**
   * Exists ReservationHomeSubCluster Mapping.
   *
   * @param reservationId reservationId
   * @return true - exist, false - not exist
   */
  public boolean existsReservationHomeSubCluster(ReservationId reservationId) {
    try {
      SubClusterId subClusterId = getReservationHomeSubCluster(reservationId);
      if (subClusterId != null) {
        return true;
      }
    } catch (YarnException e) {
      LOG.warn("get homeSubCluster by reservationId = {} error.", reservationId, e);
    }
    return false;
  }

  /**
   * Save Reservation And HomeSubCluster Mapping.
   *
   * @param reservationId reservationId
   * @param homeSubCluster homeSubCluster
   * @throws YarnException on failure
   */
  public void addReservationHomeSubCluster(ReservationId reservationId,
      ReservationHomeSubCluster homeSubCluster) throws YarnException {
    try {
      // persist the mapping of reservationId and the subClusterId which has
      // been selected as its home
      addReservationHomeSubCluster(homeSubCluster);
    } catch (YarnException e) {
      String msg = String.format(
          "Unable to insert the ReservationId %s into the FederationStateStore.", reservationId);
      throw new YarnException(msg, e);
    }
  }

  /**
   * Update Reservation And HomeSubCluster Mapping.
   *
   * @param subClusterId subClusterId
   * @param reservationId reservationId
   * @param homeSubCluster homeSubCluster
   * @throws YarnException on failure
   */
  public void updateReservationHomeSubCluster(SubClusterId subClusterId,
      ReservationId reservationId, ReservationHomeSubCluster homeSubCluster) throws YarnException {
    try {
      // update the mapping of reservationId and the home subClusterId to
      // the new subClusterId we have selected
      updateReservationHomeSubCluster(homeSubCluster);
    } catch (YarnException e) {
      SubClusterId subClusterIdInStateStore = getReservationHomeSubCluster(reservationId);
      if (subClusterId == subClusterIdInStateStore) {
        LOG.info("Reservation {} already submitted on SubCluster {}.", reservationId, subClusterId);
      } else {
        String msg = String.format(
            "Unable to update the ReservationId %s into the FederationStateStore.", reservationId);
        throw new YarnException(msg, e);
      }
    }
  }

  /**
   * Add or Update ReservationHomeSubCluster.
   *
   * @param reservationId reservationId.
   * @param subClusterId homeSubClusterId, this is selected by strategy.
   * @param retryCount number of retries.
   * @throws YarnException yarn exception.
   */
  public void addOrUpdateReservationHomeSubCluster(ReservationId reservationId,
      SubClusterId subClusterId, int retryCount) throws YarnException {
    Boolean exists = existsReservationHomeSubCluster(reservationId);
    ReservationHomeSubCluster reservationHomeSubCluster =
        ReservationHomeSubCluster.newInstance(reservationId, subClusterId);
    if (!exists || retryCount == 0) {
      // persist the mapping of reservationId and the subClusterId which has
      // been selected as its home.
      addReservationHomeSubCluster(reservationId, reservationHomeSubCluster);
    } else {
      // update the mapping of reservationId and the home subClusterId to
      // the new subClusterId we have selected.
      updateReservationHomeSubCluster(subClusterId, reservationId,
          reservationHomeSubCluster);
    }
  }

  /**
   * Deregister subCluster, Update the subCluster state to
   * SC_LOST、SC_DECOMMISSIONED etc.
   *
   * @param subClusterId subClusterId.
   * @param subClusterState The state of the subCluster to be updated.
   * @throws YarnException yarn exception.
   * @return If Deregister subCluster is successful, return true, otherwise, return false.
   */
  public boolean deregisterSubCluster(SubClusterId subClusterId,
      SubClusterState subClusterState) throws YarnException {
    SubClusterDeregisterRequest deregisterRequest =
        SubClusterDeregisterRequest.newInstance(subClusterId, subClusterState);
    SubClusterDeregisterResponse response = stateStore.deregisterSubCluster(deregisterRequest);
    // If the response is not empty, deregisterSubCluster is successful.
    if (response != null) {
      return true;
    }
    return false;
  }
}
