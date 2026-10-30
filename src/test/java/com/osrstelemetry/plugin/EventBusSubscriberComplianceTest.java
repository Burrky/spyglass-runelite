package com.osrstelemetry.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.Multimap;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import org.junit.Test;

/**
 * LIVE STARTUP GUARD: every {@code @Subscribe} method in the plugin must pass
 * RuneLite's own {@link EventBus#register} validation, or RuneLite stops the
 * whole plugin at startup ("Subscribed method ... should be named on...").
 *
 * <p>RuneLite's EventBus (1.13.x) walks the registered object's class AND
 * every superclass, validates each {@code @Subscribe} method, and binds it
 * with a virtual method handle. Two consequences this test pins:
 * <ol>
 * <li>Names: exactly {@code "on" + EventClass.getSimpleName()}; one non-
 * primitive, concrete event parameter; void; not static.</li>
 * <li>Inheritance: a subclass that OVERRIDES an inherited subscriber must
 * NOT annotate the override again. Both declarations would be registered
 * and both dispatch virtually to the override, so it would run twice per
 * event. The superclass's {@code @Subscribe} already delivers every event
 * to the override.</li>
 * </ol>
 * Classes are discovered from the compiled main output, so every future
 * collector is covered without being listed here.
 */
public class EventBusSubscriberComplianceTest
{
	private static List<Class<?>> pluginClasses() throws IOException, URISyntaxException
	{
		Path root = Paths.get(OsrsTelemetryPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		List<Class<?>> out = new ArrayList<>();
		try (Stream<Path> files = Files.walk(root))
		{
			for (Path p : files.filter(f -> f.toString().endsWith(".class")).collect(Collectors.toList()))
			{
				String name = root.relativize(p).toString().replace('\\', '/').replace('/', '.');
				name = name.substring(0, name.length() - ".class".length());
				try
				{
					out.add(Class.forName(name, false, OsrsTelemetryPlugin.class.getClassLoader()));
				}
				catch (ClassNotFoundException | LinkageError e)
				{
					throw new AssertionError("could not load " + name, e);
				}
			}
		}
		assertTrue("the plugin's own classes were found", out.size() > 100);
		return out;
	}

	private static List<Method> subscribers(Class<?> c)
	{
		List<Method> out = new ArrayList<>();
		for (Method m : c.getDeclaredMethods())
		{
			if (m.isAnnotationPresent(Subscribe.class) && !m.isSynthetic())
			{
				out.add(m);
			}
		}
		return out;
	}

	private static boolean hasSubscribers(Class<?> c)
	{
		for (Class<?> k = c; k != null; k = k.getSuperclass())
		{
			if (!subscribers(k).isEmpty())
			{
				return true;
			}
		}
		return false;
	}

	@Test
	public void everySubscriber_isNamedOnEventSimpleName_withRuneLitesSignature() throws Exception
	{
		List<String> violations = new ArrayList<>();
		int checked = 0;
		for (Class<?> c : pluginClasses())
		{
			for (Method m : subscribers(c))
			{
				checked++;
				String where = c.getName() + "." + m.getName();
				if (m.getParameterCount() != 1)
				{
					violations.add(where + ": must take exactly 1 argument");
					continue;
				}
				Class<?> event = m.getParameterTypes()[0];
				String expected = "on" + event.getSimpleName();
				if (!m.getName().equals(expected))
				{
					violations.add(where + "(" + event.getSimpleName() + ") should be named " + expected);
				}
				if (m.getReturnType() != Void.TYPE)
				{
					violations.add(where + ": must return void");
				}
				if (Modifier.isStatic(m.getModifiers()))
				{
					violations.add(where + ": must not be static");
				}
				if (event.isPrimitive() || (event.getModifiers() & (Modifier.ABSTRACT | Modifier.INTERFACE)) != 0)
				{
					violations.add(where + ": must subscribe to a concrete event class");
				}
			}
		}
		assertTrue("subscribers were found", checked > 20);
		assertTrue("RuneLite would refuse to start Spyglass:\n" + String.join("\n", violations), violations.isEmpty());
	}

	@Test
	public void noOverrideReannotatesAnInheritedSubscriber_soNothingRunsTwice() throws Exception
	{
		List<String> violations = new ArrayList<>();
		for (Class<?> c : pluginClasses())
		{
			for (Method m : subscribers(c))
			{
				for (Class<?> up = c.getSuperclass(); up != null; up = up.getSuperclass())
				{
					for (Method inherited : subscribers(up))
					{
						if (inherited.getName().equals(m.getName())
							&& java.util.Arrays.equals(inherited.getParameterTypes(), m.getParameterTypes()))
						{
							violations.add(c.getName() + "." + m.getName() + " re-annotates " + up.getSimpleName() + "'s subscriber"
								+ " (remove @Subscribe from the override: the inherited one already delivers to it)");
						}
					}
				}
			}
		}
		assertTrue(String.join("\n", violations), violations.isEmpty());
	}

	/**
	 * The real thing: every concrete plugin class that subscribes to anything
	 * (its own methods or inherited ones) is registered on ONE real RuneLite
	 * EventBus, exactly as plugin startup does. Instances are allocated
	 * without running constructors; registration only reads the class.
	 */
	@Test
	public void everySubscriberClass_registersOnARealRuneLiteEventBus_once() throws Exception
	{
		Object unsafe = unsafe();
		Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
		EventBus bus = new EventBus();
		List<String> failures = new ArrayList<>();
		Map<Object, Class<?>> registered = new IdentityHashMap<>();
		for (Class<?> c : pluginClasses())
		{
			if (c.isInterface() || Modifier.isAbstract(c.getModifiers()) || c.isEnum() || c.isAnnotation() || !hasSubscribers(c))
			{
				continue;
			}
			try
			{
				Object instance = allocate.invoke(unsafe, c);
				bus.register(instance);
				registered.put(instance, c);
			}
			catch (RuntimeException e)
			{
				failures.add(c.getName() + ": " + e.getMessage());
			}
		}
		assertTrue("RuneLite's EventBus refused:\n" + String.join("\n", failures), failures.isEmpty());
		assertFalse("subscriber classes were registered", registered.isEmpty());

		// One subscription per (object, event, handler name): an override is never delivered twice.
		Field f = EventBus.class.getDeclaredField("subscribers");
		f.setAccessible(true);
		Multimap<Class<?>, EventBus.Subscriber> subs = (Multimap<Class<?>, EventBus.Subscriber>) f.get(bus);
		Map<String, Integer> counts = new HashMap<>();
		for (Map.Entry<Class<?>, EventBus.Subscriber> e : subs.entries())
		{
			Object owner = e.getValue().getObject();
			String key = registered.get(owner).getName() + "@" + System.identityHashCode(owner) + " " + e.getKey().getSimpleName() + " "
				+ e.getValue().getMethod().getName();
			counts.merge(key, 1, Integer::sum);
		}
		List<String> twice = new ArrayList<>();
		counts.forEach((k, n) ->
		{
			if (n > 1)
			{
				twice.add(k + " x" + n);
			}
		});
		assertTrue("delivered more than once per event:\n" + String.join("\n", twice), twice.isEmpty());
	}

	// ---- the RuneLite behaviour the rules above rely on (fails loudly if a RuneLite update changes it) ----

	static class Base
	{
		int calls;

		@Subscribe
		public void onGameTick(GameTick e)
		{
			calls++;
		}
	}

	static class ReannotatedOverride extends Base
	{
		@Override
		@Subscribe
		public void onGameTick(GameTick e)
		{
			super.onGameTick(e);
		}
	}

	static class PlainOverride extends Base
	{
		int own;

		@Override
		public void onGameTick(GameTick e)
		{
			own++;
			super.onGameTick(e);
		}
	}

	@Test
	public void runeLiteEventBus_reannotatedOverrideRunsTwice_plainOverrideRunsOnce()
	{
		EventBus bus = new EventBus();
		ReannotatedOverride twice = new ReannotatedOverride();
		PlainOverride once = new PlainOverride();
		bus.register(twice);
		bus.register(once);
		bus.post(new GameTick());
		assertEquals("why overrides must not be re-annotated", 2, twice.calls);
		assertEquals("the inherited subscription reaches the override", 1, once.own);
		assertEquals(1, once.calls);
	}

	private static Object unsafe() throws Exception
	{
		Class<?> u = Class.forName("sun.misc.Unsafe");
		Field f = u.getDeclaredField("theUnsafe");
		f.setAccessible(true);
		return f.get(null);
	}
}
