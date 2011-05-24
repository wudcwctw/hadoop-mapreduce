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

package org.apache.hadoop.yarn.webapp;

import static com.google.common.base.Preconditions.*;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.servlet.GuiceFilter;

import java.net.ConnectException;
import java.net.URL;
import org.apache.commons.lang.StringUtils;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.http.HttpServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helpers to create an embedded webapp.
 *
 * <h4>Quick start:</h4>
 * <pre>
 *   WebApp wa = WebApps.$for(myApp).start();</pre>
 * Starts a webapp with default routes binds to 0.0.0.0 (all network interfaces)
 * on an ephemeral port, which can be obtained with:<pre>
 *   int port = wa.port();</pre>
 * <h4>With more options:</h4>
 * <pre>
 *   WebApp wa = WebApps.$for(myApp).at(address, port).
 *                        with(configuration).
 *                        start(new WebApp() {
 *     &#064;Override public void setup() {
 *       route("/foo/action", FooController.class);
 *       route("/foo/:id", FooController.class, "show");
 *     }
 *   });</pre>
 */
public class WebApps {
  static final Logger LOG = LoggerFactory.getLogger(WebApps.class);

  public static class Builder<T> {
    final String name;
    final Class<T> api;
    final T application;
    String bindAddress = "0.0.0.0";
    int port = 0;
    boolean findPort = false;
    Configuration conf;
    boolean devMode = false;
    Module[] modules;

    Builder(String name, Class<T> api, T application) {
      this.name = name;
      this.api = api;
      this.application = application;
    }

    public Builder<T> at(String bindAddress) {
      String[] parts = StringUtils.split(bindAddress, ':');
      if (parts.length == 2) {
        return at(parts[0], Integer.parseInt(parts[1]), true);
      }
      return at(bindAddress, 0, true);
    }

    public Builder<T> at(int port) {
      return at("0.0.0.0", port, false);
    }

    public Builder<T> at(String address, int port, boolean findPort) {
      this.bindAddress = checkNotNull(address, "bind address");
      this.port = port;
      this.findPort = findPort;
      return this;
    }

    public Builder<T> with(Configuration conf) {
      this.conf = conf;
      return this;
    }

    public Builder<T> with(Module... modules) {
      this.modules = modules; // OK
      return this;
    }

    public Builder<T> inDevMode() {
      devMode = true;
      return this;
    }

    public WebApp start(WebApp webapp) {
      if (webapp == null) {
        webapp = new WebApp() {
          @Override
          public void setup() {
            // Defaults should be fine in usual cases
          }
        };
      }
      webapp.setName(name);
      if (conf == null) {
        conf = new Configuration();
      }
      try {
        if (application != null) {
          webapp.setHostClass(application.getClass());
        } else {
          String cls = inferHostClass();
          LOG.debug("setting webapp host class to {}", cls);
          webapp.setHostClass(Class.forName(cls));
        }
        if (devMode) {
          if (port > 0) {
            try {
              new URL("http://localhost:"+ port +"/__stop").getContent();
              LOG.info("stopping existing webapp instance");
              Thread.sleep(100);
            } catch (ConnectException e) {
              LOG.info("no existing webapp instance found: {}", e.toString());
            } catch (Exception e) {
              // should not be fatal
              LOG.warn("error stopping existing instance: {}", e.toString());
            }
          } else {
            LOG.error("dev mode does NOT work with ephemeral port!");
            System.exit(1);
          }
        }
        HttpServer server =
            new HttpServer(name, bindAddress, port, findPort, conf);
        server.addGlobalFilter("guice", GuiceFilter.class.getName(), null);
        webapp.setConf(conf);
        webapp.setHttpServer(server);
        server.start();
        LOG.info("Web app /"+ name +" started at "+ server.getPort());
      } catch (Exception e) {
        throw new WebAppException("Error starting http server", e);
      }
      Injector injector = Guice.createInjector(webapp, new AbstractModule() {
        @Override @SuppressWarnings("unchecked")
        protected void configure() {
          if (api != null) {
            bind(api).toInstance(application);
          }
        }
      });
      LOG.info("Registered webapp guice modules");
      // save a guice filter instance for webapp stop (mostly for unit tests)
      webapp.setGuiceFilter(injector.getInstance(GuiceFilter.class));
      if (devMode) {
        injector.getInstance(Dispatcher.class).setDevMode(devMode);
        LOG.info("in dev mode!");
      }
      return webapp;
    }

    public WebApp start() {
      return start(null);
    }

    private String inferHostClass() {
      String thisClass = this.getClass().getName();
      Throwable t = new Throwable();
      for (StackTraceElement e : t.getStackTrace()) {
        if (e.getClassName().equals(thisClass)) continue;
        return e.getClassName();
      }
      LOG.warn("could not infer host class from", t);
      return thisClass;
    }
  }

  /**
   * Create a new webapp builder.
   * @see WebApps for a complete example
   * @param <T> application (holding the embedded webapp) type
   * @param prefix of the webapp
   * @param api the api class for the application
   * @param app the application instance
   * @return a webapp builder
   */
  public static <T> Builder<T> $for(String prefix, Class<T> api, T app) {
    return new Builder<T>(prefix, api, app);
  }

  // Short cut mostly for tests/demos
  @SuppressWarnings("unchecked")
  public static <T> Builder<T> $for(String prefix, T app) {
    return $for(prefix, (Class<T>)app.getClass(), app);
  }

  // Ditto
  @SuppressWarnings("unchecked")
  public static <T> Builder<T> $for(T app) {
    return $for("", app);
  }

  public static <T> Builder<T> $for(String prefix) {
    return $for(prefix, null, null);
  }
}