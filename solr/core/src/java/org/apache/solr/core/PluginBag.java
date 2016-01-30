package org.apache.solr.core;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.analysis.util.ResourceLoader;
import org.apache.lucene.analysis.util.ResourceLoaderAware;
import org.apache.solr.cloud.CloudUtil;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.Lookup;
import org.apache.solr.common.util.Map2;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.handler.component.SearchComponent;
import org.apache.solr.request.SolrRequestHandler;
import org.apache.solr.util.CryptoKeys;
import org.apache.solr.util.plugin.NamedListInitializedPlugin;
import org.apache.solr.util.plugin.PluginInfoInitialized;
import org.apache.solr.util.plugin.SolrCoreAware;
import org.apache.solr.v2api.ApiBag;
import org.apache.solr.v2api.V2Api;
import org.apache.solr.v2api.V2ApiSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Collections.singletonMap;
import static org.apache.solr.common.params.CommonParams.NAME;
import static org.apache.solr.v2api.ApiBag.HANDLER_NAME;

/**
 * This manages the lifecycle of a set of plugin of the same type .
 */
public class PluginBag<T> implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final Map<String, PluginHolder<T>> registry;
  private final Map<String, PluginHolder<T>> immutableRegistry;
  private String def;
  private final Class klass;
  private SolrCore core;
  private final SolrConfig.SolrPluginInfo meta;
  private final ApiBag apiBag;

  /**
   * Pass needThreadSafety=true if plugins can be added and removed concurrently with lookups.
   */
  public PluginBag(Class<T> klass, SolrCore core, boolean needThreadSafety) {
    this.apiBag = klass == SolrRequestHandler.class ? new ApiBag() : null;
    this.core = core;
    this.klass = klass;
    // TODO: since reads will dominate writes, we could also think about creating a new instance of a map each time it changes.
    // Not sure how much benefit this would have over ConcurrentHashMap though
    // We could also perhaps make this constructor into a factory method to return different implementations depending on thread safety needs.
    this.registry = needThreadSafety ? new ConcurrentHashMap<>() : new HashMap<>();
    this.immutableRegistry = Collections.unmodifiableMap(registry);
    meta = SolrConfig.classVsSolrPluginInfo.get(klass.getName());
    if (meta == null) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Unknown Plugin : " + klass.getName());
    }
  }

  /**
   * Constructs a non-threadsafe plugin registry
   */
  public PluginBag(Class<T> klass, SolrCore core) {
    this(klass, core, false);
  }

  static void initInstance(Object inst, PluginInfo info) {
    if (inst instanceof PluginInfoInitialized) {
      ((PluginInfoInitialized) inst).init(info);
    } else if (inst instanceof NamedListInitializedPlugin) {
      ((NamedListInitializedPlugin) inst).init(info.initArgs);
    } else if (inst instanceof SolrRequestHandler) {
      ((SolrRequestHandler) inst).init(info.initArgs);
    }
    if (inst instanceof SearchComponent) {
      ((SearchComponent) inst).setName(info.name);
    }
    if (inst instanceof RequestHandlerBase) {
      ((RequestHandlerBase) inst).setPluginInfo(info);
    }

  }

  /**
   * Check if any of the mentioned names are missing. If yes, return the Set of missing names
   */
  public Set<String> checkContains(Collection<String> names) {
    if (names == null || names.isEmpty()) return Collections.EMPTY_SET;
    HashSet<String> result = new HashSet<>();
    for (String s : names) if (!this.registry.containsKey(s)) result.add(s);
    return result;
  }

  PluginHolder<T> createPlugin(PluginInfo info) {
    if ("true".equals(String.valueOf(info.attributes.get("runtimeLib")))) {
      log.info(" {} : '{}'  created with runtimeLib=true ", meta.getCleanTag(), info.name);
      return new LazyPluginHolder<>(meta, info, core, core.getMemClassLoader());
    } else if ("lazy".equals(info.attributes.get("startup")) && meta.options.contains(SolrConfig.PluginOpts.LAZY)) {
      log.info("{} : '{}' created with startup=lazy ", meta.getCleanTag(), info.name);
      return new LazyPluginHolder<T>(meta, info, core, core.getResourceLoader());
    } else {
      T inst = core.createInstance(info.className, (Class<T>) meta.clazz, meta.getCleanTag(), null, core.getResourceLoader());
      initInstance(inst, info);
      return new PluginHolder<>(info, inst);
    }
  }

  /** make a plugin available in an alternate name. This is an internal API and not for public use
   * @param src key in which the plugin is already registered
   * @param target the new key in which the plugin should be aliased to. If target exists already, the alias fails
   * @return flag if the operation is successful or not
   */
  boolean alias(String src, String target) {
    if (src == null) return false;
    PluginHolder<T> a = registry.get(src);
    if (a == null) return false;
    PluginHolder<T> b = registry.get(target);
    if (b != null) return false;
    registry.put(target, a);
    return true;
  }

  /**
   * Get a plugin by name. If the plugin is not already instantiated, it is
   * done here
   */
  public T get(String name) {
    PluginHolder<T> result = registry.get(name);
    return result == null ? null : result.get();
  }

  /**
   * Fetches a plugin by name , or the default
   *
   * @param name       name using which it is registered
   * @param useDefault Return the default , if a plugin by that name does not exist
   */
  public T get(String name, boolean useDefault) {
    T result = get(name);
    if (useDefault && result == null) return get(def);
    return result;
  }

  public Set<String> keySet() {
    return immutableRegistry.keySet();
  }

  /**
   * register a plugin by a name
   */
  public T put(String name, T plugin) {
    if (plugin == null) return null;
    PluginHolder<T> old = put(name, new PluginHolder<T>(null, plugin));
    return old == null ? null : old.get();
  }


  PluginHolder<T> put(String name, PluginHolder<T> plugin) {
    PluginHolder<T> old = registry.put(name, plugin);
    if (plugin.pluginInfo != null && plugin.pluginInfo.isDefault()) {
      setDefault(name);
    }

    if (apiBag != null) {
      if (plugin.isLoaded()) {
        T inst = plugin.get();
        Collection<V2Api> apis = ((V2ApiSupport) inst).getApis(apiBag.getSpecLookup());
        if (apis != null) {
          Map<String, String> nameSubstitutes = singletonMap(HANDLER_NAME, name);
          for (V2Api api : apis) {
            apiBag.register(api, nameSubstitutes);
          }
        }
      } else {
        apiBag.registerLazy((PluginHolder<SolrRequestHandler>) plugin, plugin.pluginInfo);
      }
    }
    if (plugin.isLoaded()) registerMBean(plugin.get(), core, name);
    return old;
  }

  void setDefault(String def) {
    if (!registry.containsKey(def)) return;
    if (this.def != null) log.warn("Multiple defaults for : " + meta.getCleanTag());
    this.def = def;
  }

  public Map<String, PluginHolder<T>> getRegistry() {
    return immutableRegistry;
  }

  public boolean contains(String name) {
    return registry.containsKey(name);
  }

  String getDefault() {
    return def;
  }

  T remove(String name) {
    PluginHolder<T> removed = registry.remove(name);
    return removed == null ? null : removed.get();
  }

  void init(Map<String, T> defaults, SolrCore solrCore) {
    init(defaults, solrCore, solrCore.getSolrConfig().getPluginInfos(klass.getName()));
  }

  /**
   * Initializes the plugins after reading the meta data from {@link org.apache.solr.core.SolrConfig}.
   *
   * @param defaults These will be registered if not explicitly specified
   */
  void init(Map<String, T> defaults, SolrCore solrCore, List<PluginInfo> infos) {
    core = solrCore;
    for (PluginInfo info : infos) {
      PluginHolder<T> o = createPlugin(info);
      String name = info.name;
      if (meta.clazz.equals(SolrRequestHandler.class)) name = RequestHandlers.normalize(info.name);
      PluginHolder<T> old = put(name, o);
      if (old != null) log.warn("Multiple entries of {} with name {}", meta.getCleanTag(), name);
    }
    for (Map.Entry<String, T> e : defaults.entrySet()) {
      if (!contains(e.getKey())) {
        put(e.getKey(), new PluginHolder<T>(null, e.getValue()));
      }
    }
  }

  /**
   * To check if a plugin by a specified name is already loaded
   */
  public boolean isLoaded(String name) {
    PluginHolder<T> result = registry.get(name);
    if (result == null) return false;
    return result.isLoaded();
  }

  private void registerMBean(Object inst, SolrCore core, String pluginKey) {
    if (core == null) return;
    if (inst instanceof SolrInfoMBean) {
      SolrInfoMBean mBean = (SolrInfoMBean) inst;
      String name = (inst instanceof SolrRequestHandler) ? pluginKey : mBean.getName();
      core.registerInfoBean(name, mBean);
    }
  }


  /**
   * Close this registry. This will in turn call a close on all the contained plugins
   */
  @Override
  public void close() {
    for (Map.Entry<String, PluginHolder<T>> e : registry.entrySet()) {
      try {
        e.getValue().close();
      } catch (Exception exp) {
        log.error("Error closing plugin " + e.getKey() + " of type : " + meta.getCleanTag(), exp);
      }
    }
  }

  /**
   * An indirect reference to a plugin. It just wraps a plugin instance.
   * subclasses may choose to lazily load the plugin
   */
  public static class PluginHolder<T> implements AutoCloseable {
    private T inst;
    protected final PluginInfo pluginInfo;

    public PluginHolder(PluginInfo info) {
      this.pluginInfo = info;
    }

    public PluginHolder(PluginInfo info, T inst) {
      this.inst = inst;
      this.pluginInfo = info;
    }

    public T get() {
      return inst;
    }

    public boolean isLoaded() {
      return inst != null;
    }

    @Override
    public void close() throws Exception {
      // TODO: there may be a race here.  One thread can be creating a plugin
      // and another thread can come along and close everything (missing the plugin
      // that is in the state of being created and will probably never have close() called on it).
      // can close() be called concurrently with other methods?
      if (isLoaded()) {
        T myInst = get();
        if (myInst != null && myInst instanceof AutoCloseable) ((AutoCloseable) myInst).close();
      }
    }

    public String getClassName() {
      if (isLoaded()) return inst.getClass().getName();
      if (pluginInfo != null) return pluginInfo.className;
      return null;
    }
  }

  /**
   * A class that loads plugins Lazily. When the get() method is invoked
   * the Plugin is initialized and returned.
   */
  public class LazyPluginHolder<T> extends PluginHolder<T> {
    private volatile T lazyInst;
    private final SolrConfig.SolrPluginInfo pluginMeta;
    protected SolrException solrException;
    private final SolrCore core;
    protected ResourceLoader resourceLoader;


    LazyPluginHolder(SolrConfig.SolrPluginInfo pluginMeta, PluginInfo pluginInfo, SolrCore core, ResourceLoader loader) {
      super(pluginInfo);
      this.pluginMeta = pluginMeta;
      this.core = core;
      this.resourceLoader = loader;
      if (loader instanceof MemClassLoader) {
        if (!"true".equals(System.getProperty("enable.runtime.lib"))) {
          String s = "runtime library loading is not enabled, start Solr with -Denable.runtime.lib=true";
          log.warn(s);
          solrException = new SolrException(SolrException.ErrorCode.SERVER_ERROR, s);
        }
      }
    }

    @Override
    public boolean isLoaded() {
      return lazyInst != null;
    }

    @Override
    public T get() {
      if (lazyInst != null) return lazyInst;
      if (solrException != null) {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,"Unrecoverable error", solrException);
      }
      if (createInst()) {
        // check if we created the instance to avoid registering it again
        registerMBean(lazyInst, core, pluginInfo.name);
      }
      return lazyInst;
    }

    private synchronized boolean createInst() {
      if (lazyInst != null) return false;
      log.info("Going to create a new {} with {} ", pluginMeta.getCleanTag(), pluginInfo.toString());
      if (resourceLoader instanceof MemClassLoader) {
        MemClassLoader loader = (MemClassLoader) resourceLoader;
        loader.loadJars();
      }
      Class<T> clazz = (Class<T>) pluginMeta.clazz;
      T localInst = core.createInstance(pluginInfo.className, clazz, pluginMeta.getCleanTag(), null, resourceLoader);
      initInstance(localInst, pluginInfo);
      if (localInst instanceof SolrCoreAware) {
        SolrResourceLoader.assertAwareCompatibility(SolrCoreAware.class, localInst);
        ((SolrCoreAware) localInst).inform(core);
      }
      if (localInst instanceof ResourceLoaderAware) {
        SolrResourceLoader.assertAwareCompatibility(ResourceLoaderAware.class, localInst);
        try {
          ((ResourceLoaderAware) localInst).inform(core.getResourceLoader());
        } catch (IOException e) {
          throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "error initializing component", e);
        }
      }
      lazyInst = localInst;  // only assign the volatile until after the plugin is completely ready to use
      return true;
    }


  }

  /**
   * This represents a Runtime Jar. A jar requires two details , name and version
   */
  public static class RuntimeLib implements PluginInfoInitialized, AutoCloseable {
    private String name, version, sig;
    private JarRepository.JarContentRef jarContent;
    private final CoreContainer coreContainer;
    private boolean verified = false;

    @Override
    public void init(PluginInfo info) {
      name = info.attributes.get(NAME);
      Object v = info.attributes.get("version");
      if (name == null || v == null) {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "runtimeLib must have name and version");
      }
      version = String.valueOf(v);
      sig = info.attributes.get("sig");
    }

    public RuntimeLib(SolrCore core) {
      coreContainer = core.getCoreDescriptor().getCoreContainer();
    }


    void loadJar() {
      if (jarContent != null) return;
      synchronized (this) {
        if (jarContent != null) return;
        jarContent = coreContainer.getJarRepository().getJarIncRef(name + "/" + version);
      }
    }

    public String getName() {
      return name;
    }

    public String getVersion() {
      return version;
    }

    public String getSig() {
      return sig;

    }

    public ByteBuffer getFileContent(String entryName) throws IOException {
      if (jarContent == null)
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "jar not available: " + name + "/" + version);
      return jarContent.jar.getFileContent(entryName);

    }

    @Override
    public void close() throws Exception {
      if (jarContent != null) coreContainer.getJarRepository().decrementJarRefCount(jarContent);
    }

    public static List<RuntimeLib> getLibObjects(SolrCore core, List<PluginInfo> libs) {
      List<RuntimeLib> l = new ArrayList<>(libs.size());
      for (PluginInfo lib : libs) {
        RuntimeLib rtl = new RuntimeLib(core);
        rtl.init(lib);
        l.add(rtl);
      }
      return l;
    }

    public void verify() throws Exception {
      if (verified) return;
      if (jarContent == null) {
        log.error("Calling verify before loading the jar");
        return;
      }

      if (!coreContainer.isZooKeeperAware())
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Signing jar is possible only in cloud");
      Map<String, byte[]> keys = CloudUtil.getTrustedKeys(coreContainer.getZkController().getZkClient(), "exe");
      if (keys.isEmpty()) {
        if (sig == null) {
          verified = true;
          log.info("A run time lib {} is loaded  without verification ", name);
          return;
        } else {
          throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "No public keys are available in ZK to verify signature for runtime lib  " + name);
        }
      } else if (sig == null) {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, StrUtils.formatString("runtimelib {0} should be signed with one of the keys in ZK /keys/exe ", name));
      }

      try {
        String matchedKey = jarContent.jar.checkSignature(sig, new CryptoKeys(keys));
        if (matchedKey == null)
          throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "No key matched signature for jar : " + name + " version: " + version);
        log.info("Jar {} signed with {} successfully verified", name, matchedKey);
      } catch (Exception e) {
        if (e instanceof SolrException) throw e;
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error verifying key ", e);
      }
    }
  }


  public V2Api v2lookup(String path, String method, Map<String, String> parts) {
    if (apiBag == null) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "this should not happen, looking up for v2 API at the wrong place");
    }
    return apiBag.lookup(path, method, parts);
  }

  public ApiBag getApiBag() {
    return apiBag;
  }

}
