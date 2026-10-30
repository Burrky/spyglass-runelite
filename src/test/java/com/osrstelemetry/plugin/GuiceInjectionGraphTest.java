package com.osrstelemetry.plugin;

import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * LIVE STARTUP GUARD (companion to EventBusSubscriberComplianceTest): RuneLite
 * builds the plugin with Guice. Every Spyglass class reachable from the
 * plugin's injected fields / constructors must be constructible by Guice's
 * just-in-time binding -- a concrete class with exactly one {@code @Inject}
 * constructor, or a non-private no-argument one -- or the plugin fails to
 * start. RuneLite's own types (Client, ConfigManager...) are bound by RuneLite
 * and are not checked here; neither are Provider / Supplier indirections.
 */
public class GuiceInjectionGraphTest
{
	private static boolean injectAnnotated(java.lang.reflect.AnnotatedElement e)
	{
		return e.isAnnotationPresent(javax.inject.Inject.class) || e.isAnnotationPresent(com.google.inject.Inject.class);
	}

	private static boolean ours(Class<?> c)
	{
		return c.getName().startsWith("com.osrstelemetry.");
	}

	@Test
	public void everySpyglassClassThePluginInjects_isConstructibleByGuice()
	{
		List<String> problems = new ArrayList<>();
		Set<Class<?>> seen = new HashSet<>();
		// Types the plugin binds itself (@Provides, e.g. its config interface) are never JIT-constructed.
		for (java.lang.reflect.Method m : OsrsTelemetryPlugin.class.getDeclaredMethods())
		{
			if (m.isAnnotationPresent(com.google.inject.Provides.class))
			{
				seen.add(m.getReturnType());
			}
		}
		Deque<Class<?>> todo = new ArrayDeque<>();
		todo.add(OsrsTelemetryPlugin.class);
		while (!todo.isEmpty())
		{
			Class<?> c = todo.poll();
			if (!seen.add(c))
			{
				continue;
			}
			List<Type> deps = new ArrayList<>();
			for (Class<?> k = c; k != null && ours(k); k = k.getSuperclass())
			{
				for (Field f : k.getDeclaredFields())
				{
					if (injectAnnotated(f))
					{
						deps.add(f.getGenericType());
					}
				}
			}
			if (c != OsrsTelemetryPlugin.class)
			{
				if (c.isInterface() || Modifier.isAbstract(c.getModifiers()))
				{
					if (!c.isAnnotationPresent(com.google.inject.ImplementedBy.class))
					{
						problems.add(c.getName() + ": abstract / interface with no binding");
					}
					continue;
				}
				List<Constructor<?>> injectCtors = new ArrayList<>();
				Constructor<?> noArg = null;
				for (Constructor<?> ctor : c.getDeclaredConstructors())
				{
					if (injectAnnotated(ctor))
					{
						injectCtors.add(ctor);
					}
					else if (ctor.getParameterCount() == 0 && !Modifier.isPrivate(ctor.getModifiers()))
					{
						noArg = ctor;
					}
				}
				if (injectCtors.size() > 1)
				{
					problems.add(c.getName() + ": more than one @Inject constructor");
				}
				else if (injectCtors.size() == 1)
				{
					for (Type t : injectCtors.get(0).getGenericParameterTypes())
					{
						deps.add(t);
					}
				}
				else if (noArg == null)
				{
					problems.add(c.getName() + ": no @Inject constructor and no non-private no-arg constructor");
				}
				if (c.getEnclosingClass() != null && !Modifier.isStatic(c.getModifiers()))
				{
					problems.add(c.getName() + ": inner (non-static) class cannot be injected");
				}
			}
			for (Type t : deps)
			{
				// Provider<X> / Supplier<X> and other generics are resolved lazily or by RuneLite: skip.
				if (t instanceof Class && ours((Class<?>) t))
				{
					todo.add((Class<?>) t);
				}
			}
		}
		assertTrue("the plugin's own collaborators were walked", seen.size() > 20);
		assertTrue("Guice could not build:\n" + String.join("\n", problems), problems.isEmpty());
	}
}
