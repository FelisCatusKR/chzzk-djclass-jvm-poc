package kr.felis.djclass.resolver;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.util.jar.JarFile;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * Native image only: Caffeine picks its generated cache/node classes (SSMSA,
 * PSAMW, ...) by name at runtime via reflection, so they must be registered at
 * build time. Runs during Spring AOT processing on the JVM.
 */
public class CaffeineHints implements RuntimeHintsRegistrar {
	@Override
	public void registerHints(RuntimeHints hints, ClassLoader cl) {
		var jar = Caffeine.class.getProtectionDomain().getCodeSource().getLocation().getPath();
		try (var jf = new JarFile(jar)) {
			jf.stream()
					.map(e -> e.getName())
					.filter(n -> n.matches("com/github/benmanes/caffeine/cache/[A-Z]+\\.class"))
					.map(n -> n.replace('/', '.').replace(".class", ""))
					.forEach(c -> hints.reflection().registerType(TypeReference.of(c),
							MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.ACCESS_DECLARED_FIELDS));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
