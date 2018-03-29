/*
 * Copyright (c) 2006 JMockit developers
 * This file is subject to the terms of the MIT license (see LICENSE.txt).
 */
package mockit.coverage;

import java.io.*;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;
import javax.annotation.*;
import static java.lang.reflect.Modifier.*;

import mockit.internal.util.*;

public final class CallPoint implements Serializable
{
   private static final long serialVersionUID = 362727169057343840L;
   private static final Map<StackTraceElement, Boolean> steCache = new HashMap<>();
   private static final Class<? extends Annotation> testAnnotation;
   private static final boolean checkTestAnnotationOnClass;
   private static final boolean checkIfTestCaseSubclass;

   static {
      boolean checkOnClassAlso = true;
      Class<?> annotation;

      try {
         annotation = Class.forName("org.junit.Test");
         checkOnClassAlso = false;
      }
      catch (ClassNotFoundException ignore) {
         annotation = getTestNGAnnotationIfAvailable();
      }

      //noinspection unchecked
      testAnnotation = (Class<? extends Annotation>) annotation;
      checkTestAnnotationOnClass = checkOnClassAlso;
      checkIfTestCaseSubclass = checkForJUnit3Availability();
   }

   @Nullable
   private static Class<?> getTestNGAnnotationIfAvailable() {
      try {
         return Class.forName("org.testng.annotations.Test");
      }
      catch (ClassNotFoundException ignore) {
         // For older versions of TestNG:
         try { return Class.forName("org.testng.Test"); } catch (ClassNotFoundException ignored) { return null; }
      }
   }

   private static boolean checkForJUnit3Availability() {
      try {
         Class.forName("junit.framework.TestCase");
         return true;
      }
      catch (ClassNotFoundException ignore) { return false; }
   }

   @Nonnull private final StackTraceElement ste;
   private int repetitionCount;

   private CallPoint(@Nonnull StackTraceElement ste) { this.ste = ste; }

   @Nonnull public StackTraceElement getStackTraceElement() { return ste; }
   public int getRepetitionCount() { return repetitionCount; }

   public void incrementRepetitionCount() { repetitionCount++; }

   public boolean isSameTestMethod(@Nonnull CallPoint other) {
      StackTraceElement thisSTE = ste;
      StackTraceElement otherSTE = other.ste;
      return
         thisSTE == otherSTE ||
         thisSTE.getClassName().equals(otherSTE.getClassName()) &&
         thisSTE.getMethodName().equals(otherSTE.getMethodName());
   }

   public boolean isSameLineInTestCode(@Nonnull CallPoint other) {
      return isSameTestMethod(other) && ste.getLineNumber() == other.ste.getLineNumber();
   }

   @Nullable
   static CallPoint create(@Nonnull Throwable newThrowable) {
      StackTrace st = new StackTrace(newThrowable);
      int n = st.getDepth();

      for (int i = 2; i < n; i++) {
         StackTraceElement ste = st.getElement(i);

         if (isTestMethod(ste)) {
            return new CallPoint(ste);
         }
      }

      return null;
   }

   private static boolean isTestMethod(@Nonnull StackTraceElement ste) {
      String className = ste.getClassName();
      String methodName = ste.getMethodName();

      if (className == null || methodName == null) {
         return false;
      }

      if (steCache.containsKey(ste)){
         return steCache.get(ste);
      }

      boolean isTestMethod = false;

      if (ste.getFileName() != null && ste.getLineNumber() >= 0 && !isClassInExcludedPackage(className)) {
         Class<?> aClass = loadClass(className);

         if (aClass != null) {
            isTestMethod = isTestMethod(aClass, methodName);
         }
      }

      steCache.put(ste, isTestMethod);
      return isTestMethod;
   }

   private static boolean isClassInExcludedPackage(@Nonnull String className) {
      return
         className.startsWith("java.")      || className.startsWith("javax.")      || className.startsWith("sun.") ||
         className.startsWith("org.junit.") || className.startsWith("org.testng.") || className.startsWith("mockit.");
   }

   @Nullable
   private static Class<?> loadClass(@Nonnull String className) {
      try {
         return Class.forName(className);
      }
      catch (ClassNotFoundException | LinkageError ignore) { return null; }
   }

   private static boolean isTestMethod(@Nonnull Class<?> testClass, @Nonnull String methodName) {
      if (checkTestAnnotationOnClass && testClass.isAnnotationPresent(testAnnotation)) {
         return true;
      }

      Method method = findMethod(testClass, methodName);

      return method != null && (
         containsATestFrameworkAnnotation(method.getDeclaredAnnotations()) ||
         checkIfTestCaseSubclass && isJUnit3xTestMethod(testClass, method)
      );
   }

   @Nullable
   private static Method findMethod(@Nonnull Class<?> aClass, @Nonnull String name) {
      try {
         for (Method method : aClass.getDeclaredMethods()) {
            if (method.getReturnType() == void.class && name.equals(method.getName())) {
               return method;
            }
         }
      }
      catch (NoClassDefFoundError ignore) {}

      return null;
   }

   private static boolean containsATestFrameworkAnnotation(@Nonnull Annotation[] methodAnnotations) {
      for (Annotation annotation : methodAnnotations) {
         String annotationName = annotation.annotationType().getName();

         if (annotationName.startsWith("org.junit.") || annotationName.startsWith("org.testng.")) {
            return true;
         }
      }

      return false;
   }

   private static boolean isJUnit3xTestMethod(@Nonnull Class<?> aClass, @Nonnull Method method) {
      if (!isPublic(method.getModifiers()) || !method.getName().startsWith("test")) {
         return false;
      }

      Class<?> superClass = aClass.getSuperclass();

      while (superClass != Object.class) {
         if ("junit.framework.TestCase".equals(superClass.getName())) {
            return true;
         }

         superClass = superClass.getSuperclass();
      }

      return false;
   }
}
