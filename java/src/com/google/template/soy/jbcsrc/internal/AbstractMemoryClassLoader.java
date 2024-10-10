
/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.jbcsrc.internal;

import com.google.common.base.Throwables;
import com.google.errorprone.annotations.ForOverride;
import com.google.template.soy.jbcsrc.shared.CompiledTemplates.DebuggingClassLoader;
import com.google.template.soy.jbcsrc.shared.Names;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import javax.annotation.Nullable;

/** Base class to share code between our custom memory based classloader implementations. */
public abstract class AbstractMemoryClassLoader extends ClassLoader implements DebuggingClassLoader {
  private static final ProtectionDomain DEFAULT_PROTECTION_DOMAIN;

  static {
    DEFAULT_PROTECTION_DOMAIN =
        AccessController.doPrivileged(
            (PrivilegedAction<ProtectionDomain>) MemoryClassLoader.class::getProtectionDomain);
  }

  protected AbstractMemoryClassLoader() {
    this(AbstractMemoryClassLoader.class.getClassLoader());
  }

  protected AbstractMemoryClassLoader(ClassLoader classLoader) {
    super(classLoader);
  }

  /** Returns a data object for a class with the given name or {@code null} if it doesn't exist. */
  @Nullable
  @ForOverride
  protected abstract ClassData getClassData(String name);

  @Override
  public String getDebugInfoForClass(String className) {
    ClassData data = getClassData(className);
    return data != null ? "Class Data:\n" + data : null;
  }

  @Override
  public final Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    if (Names.isGenerated(name)) {
      synchronized (getClassLoadingLock(name)) {
        Class<?> loadedClass = findLoadedClassOrDefine(name);
        if (resolve) {
          resolveClass(loadedClass);
        }
        return loadedClass;
      }
    }
    return super.loadClass(name, resolve);
  }

  private Class<?> findLoadedClassOrDefine(String name) throws ClassNotFoundException {
    Class<?> loadedClass = findLoadedClass(name);
    if (loadedClass == null) {
      loadedClass = findClass(name);
    }
    return loadedClass;
  }

  @Override
  protected final Class<?> findClass(String name) throws ClassNotFoundException {
    ClassData classDef = getClassData(name);
    if (classDef == null) {
      throw new ClassNotFoundException(name);
    }
    return defineClassWithHandling(name, classDef);
  }

  private Class<?> defineClassWithHandling(String name, ClassData classDef) throws ClassNotFoundException {
    try {
      return super.defineClass(name, classDef.data(), 0, classDef.data().length, DEFAULT_PROTECTION_DOMAIN);
    } catch (Throwable t) {
      t.addSuppressed(new RuntimeException("Failed to load generated class:\n" + classDef));
      Throwables.propagateIfInstanceOf(t, ClassNotFoundException.class);
      throw Throwables.propagate(t);
    }
  }

  @Override
  protected final URL findResource(String name) {
    if (!name.endsWith(".class")) {
      return null;
    }
    String className = extractClassName(name);
    ClassData classDef = getClassData(className);
    return classDef != null ? classDef.asUrl() : null;
  }

  private String extractClassName(String resourceName) {
    return resourceName.substring(0, resourceName.length() - ".class".length()).replace('/', '.');
  }
}
