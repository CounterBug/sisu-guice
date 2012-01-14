/**
 * Copyright (C) 2006 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.inject.servlet;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.inject.Binding;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.OutOfScopeException;
import com.google.inject.Provider;
import com.google.inject.Scope;
import com.google.inject.Scopes;
import com.google.inject.internal.LinkedBindingImpl;
import com.google.inject.spi.BindingScopingVisitor;
import com.google.inject.spi.ExposedBinding;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * Servlet scopes.
 *
 * @author crazybob@google.com (Bob Lee)
 */
public class ServletScopes {

  private ServletScopes() {}

  /** Keys bound in request-scope which are handled directly by GuiceFilter. */
  private static final ImmutableSet<Key<?>> REQUEST_CONTEXT_KEYS = ImmutableSet.of(
      Key.get(HttpServletRequest.class),
      Key.get(HttpServletResponse.class),
      new Key<Map<String, String[]>>(RequestParameters.class) {});

  /**
   * A threadlocal scope map for non-http request scopes. The {@link #REQUEST}
   * scope falls back to this scope map if no http request is available, and
   * requires {@link #scopeRequest} to be called as an alertnative.
   */
  private static final ThreadLocal<Map<String, Object>> requestScopeContext
      = new ThreadLocal<Map<String, Object>>();

  /** A sentinel attribute value representing null. */
  enum NullObject { INSTANCE }

  /**
   * HTTP servlet request scope.
   */
  public static final Scope REQUEST = new Scope() {
    public <T> Provider<T> scope(final Key<T> key, final Provider<T> creator) {
      final String name = key.toString();
      return new Provider<T>() {
        public T get() {
          // Check if the alternate request scope should be used, if no HTTP
          // request is in progress.
          if (null == GuiceFilter.localContext.get()) {

            // NOTE(dhanji): We don't need to synchronize on the scope map
            // unlike the HTTP request because we're the only ones who have
            // a reference to it, and it is only available via a threadlocal.
            Map<String, Object> scopeMap = requestScopeContext.get();
            if (null != scopeMap) {
              @SuppressWarnings("unchecked")
              T t = (T) scopeMap.get(name);

              // Accounts for @Nullable providers.
              if (NullObject.INSTANCE == t) {
                return null;
              }

              if (t == null) {
                t = creator.get();
                if (!Scopes.isCircularProxy(t)) {
                  // Store a sentinel for provider-given null values.
                  scopeMap.put(name, t != null ? t : NullObject.INSTANCE);
                }
              }

              return t;
            } // else: fall into normal HTTP request scope and out of scope
              // exception is thrown.
          }

          // Always synchronize and get/set attributes on the underlying request
          // object since Filters may wrap the request and change the value of
          // {@code GuiceFilter.getRequest()}.
          //
          // This _correctly_ throws up if the thread is out of scope.
          HttpServletRequest request = GuiceFilter.getOriginalRequest();
          if (REQUEST_CONTEXT_KEYS.contains(key)) {
            // Don't store these keys as attributes, since they are handled by
            // GuiceFilter itself.
            return creator.get();
          }
          synchronized (request) {
            Object obj = request.getAttribute(name);
            if (NullObject.INSTANCE == obj) {
              return null;
            }
            @SuppressWarnings("unchecked")
            T t = (T) obj;
            if (t == null) {
              t = creator.get();
              if (!Scopes.isCircularProxy(t)) {
                request.setAttribute(name, (t != null) ? t : NullObject.INSTANCE);
              }
            }
            return t;
          }
        }

        @Override
        public String toString() {
          return String.format("%s[%s]", creator, REQUEST);
        }
      };
    }

    @Override
    public String toString() {
      return "ServletScopes.REQUEST";
    }
  };

  /**
   * HTTP session scope.
   */
  public static final Scope SESSION = new Scope() {
    public <T> Provider<T> scope(Key<T> key, final Provider<T> creator) {
      final String name = key.toString();
      return new Provider<T>() {
        public T get() {
          HttpSession session = GuiceFilter.getRequest().getSession();
          synchronized (session) {
            Object obj = session.getAttribute(name);
            if (NullObject.INSTANCE == obj) {
              return null;
            }
            @SuppressWarnings("unchecked")
            T t = (T) obj;
            if (t == null) {
              t = creator.get();
              if (!Scopes.isCircularProxy(t)) {
                session.setAttribute(name, (t != null) ? t : NullObject.INSTANCE);
              }
            }
            return t;
          }
        }
        @Override
        public String toString() {
          return String.format("%s[%s]", creator, SESSION);
        }
      };
    }

    @Override
    public String toString() {
      return "ServletScopes.SESSION";
    }
  };

  /**
   * Wraps the given callable in a contextual callable that "continues" the
   * HTTP request in another thread. This acts as a way of transporting
   * request context data from the request processing thread to to worker
   * threads.
   * <p>
   * There are some limitations:
   * <ul>
   *   <li>Derived objects (i.e. anything marked @RequestScoped will not be
   *      transported.</li>
   *   <li>State changes to the HttpServletRequest after this method is called
   *      will not be seen in the continued thread.</li>
   *   <li>Only the HttpServletRequest, ServletContext and request parameter
   *      map are available in the continued thread. The response and session
   *      are not available.</li>
   * </ul>
   *
   * @param callable code to be executed in another thread, which depends on
   *     the request scope.
   * @param seedMap the initial set of scoped instances for Guice to seed the
   *     request scope with.  To seed a key with null, use {@code null} as
   *     the value.
   * @return a callable that will invoke the given callable, making the request
   *     context available to it.
   * @throws OutOfScopeException if this method is called from a non-request
   *     thread, or if the request has completed.
   * 
   * @since 3.0
   */
  public static <T> Callable<T> continueRequest(final Callable<T> callable,
      final Map<Key<?>, Object> seedMap) {
    Preconditions.checkArgument(null != seedMap,
        "Seed map cannot be null, try passing in Collections.emptyMap() instead.");
    
    // Snapshot the seed map and add all the instances to our continuing HTTP request.
    final ContinuingHttpServletRequest continuingRequest =
        new ContinuingHttpServletRequest(GuiceFilter.getRequest());
    for (Map.Entry<Key<?>, Object> entry : seedMap.entrySet()) {
      Object value = validateAndCanonicalizeValue(entry.getKey(), entry.getValue());
      continuingRequest.setAttribute(entry.getKey().toString(), value);
    }

    return new Callable<T>() {
      private final HttpServletRequest request = continuingRequest;

      public T call() throws Exception {
        GuiceFilter.Context context = GuiceFilter.localContext.get();
        Preconditions.checkState(null == context,
            "Cannot continue request in the same thread as a HTTP request!");

        // Only set up the request continuation if we're running in a
        // new vanilla thread.
        GuiceFilter.localContext.set(new GuiceFilter.Context(request, request, null));
        try {
          return callable.call();
        } finally {
          // Clear the copied context if we set one up.
          if (null == context) {
            GuiceFilter.localContext.remove();
          }
        }
      }
    };
  }

  /**
   * Returns true if {@code binding} is request-scoped. If the binding is a
   * {@link com.google.inject.spi.LinkedKeyBinding linked key binding} and
   * belongs to an injector (i. e. it was retrieved via
   * {@link Injector#getBinding Injector.getBinding()}), then this method will
   * also return true if the target binding is request-scoped.
   */
  public static boolean isRequestScoped(Binding<?> binding) {
    do {
      boolean requestScoped = binding.acceptScopingVisitor(new BindingScopingVisitor<Boolean>() {
        @Override
        public Boolean visitNoScoping() {
          return false;
        }

        @Override
        public Boolean visitScopeAnnotation(Class<? extends Annotation> scopeAnnotation) {
          return scopeAnnotation == RequestScoped.class;
        }

        @Override
        public Boolean visitScope(Scope scope) {
          return scope == ServletScopes.REQUEST;
        }

        @Override
        public Boolean visitEagerSingleton() {
          return false;
        }
      });

      if (requestScoped) {
        return true;
      }

      if (binding instanceof LinkedBindingImpl) {
        LinkedBindingImpl<?> linkedBinding = (LinkedBindingImpl<?>) binding;
        Injector injector = linkedBinding.getInjector();
        if (injector != null) {
          binding = injector.getBinding(linkedBinding.getLinkedKey());
          continue;
        }
      } else if (binding instanceof ExposedBinding) {
        ExposedBinding<?> exposedBinding = (ExposedBinding<?>) binding;
        Injector injector = exposedBinding.getPrivateElements().getInjector();
        if (injector != null) {
          binding = injector.getBinding(exposedBinding.getKey());
          continue;
        }
      }

      return false;
    } while (true);
  }

  /**
   * Scopes the given callable inside a request scope. This is not the same
   * as the HTTP request scope, but is used if no HTTP request scope is in
   * progress. In this way, keys can be scoped as @RequestScoped and exist
   * in non-HTTP requests (for example: RPC requests) as well as in HTTP
   * request threads.
   *
   * @param callable code to be executed which depends on the request scope.
   *     Typically in another thread, but not necessarily so.
   * @param seedMap the initial set of scoped instances for Guice to seed the
   *     request scope with.  To seed a key with null, use {@code null} as
   *     the value.
   * @return a callable that when called will run inside the a request scope
   *     that exposes the instances in the {@code seedMap} as scoped keys.
   * @since 3.0
   */
  public static <T> Callable<T> scopeRequest(final Callable<T> callable,
      Map<Key<?>, Object> seedMap) {
    Preconditions.checkArgument(null != seedMap,
        "Seed map cannot be null, try passing in Collections.emptyMap() instead.");

    // Copy the seed values into our local scope map.
    final Map<String, Object> scopeMap = Maps.newHashMap();
    for (Map.Entry<Key<?>, Object> entry : seedMap.entrySet()) {
      Object value = validateAndCanonicalizeValue(entry.getKey(), entry.getValue());
      scopeMap.put(entry.getKey().toString(), value);
    }

    return new Callable<T>() {
      public T call() throws Exception {
        Preconditions.checkState(null == GuiceFilter.localContext.get(),
            "An HTTP request is already in progress, cannot scope a new request in this thread.");
        Preconditions.checkState(null == requestScopeContext.get(),
            "A request scope is already in progress, cannot scope a new request in this thread.");

        requestScopeContext.set(scopeMap);

        try {
          return callable.call();
        } finally {
          requestScopeContext.remove();
        }
      }
    };
  }

  /**
   * Validates the key and object, ensuring the value matches the key type, and
   * canonicalizing null objects to the null sentinel.
   */
  private static Object validateAndCanonicalizeValue(Key<?> key, Object object) {
    if (object == null || object == NullObject.INSTANCE) {
      return NullObject.INSTANCE;
    }

    if (!key.getTypeLiteral().getRawType().isInstance(object)) {
      throw new IllegalArgumentException("Value[" + object + "] of type["
          + object.getClass().getName() + "] is not compatible with key[" + key + "]");
    }

    return object;
  }
}
