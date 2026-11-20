package com.ayshriv.selfhostcrm.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * Fails when an entity overrides {@code onCreate} or {@code onUpdate} without calling {@code super}: that
 * would silently skip the timestamps set by {@link BaseEntity}. Reflection finds the overrides, the class
 * file is read to check for the {@code invokespecial} on the super method.
 */
class EntityLifecycleSuperCallTest {

	private static final Set<String> CALLBACKS = Set.of("onCreate", "onUpdate");

	@Test
	void entityOverridesOfLifecycleCallbacksCallSuper() throws Exception {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
		List<String> violations = new ArrayList<>();
		int overrides = 0;
		for (BeanDefinition definition : scanner.findCandidateComponents("com.ayshriv.selfhostcrm.domain")) {
			Class<?> entity = Class.forName(definition.getBeanClassName());
			for (Method method : entity.getDeclaredMethods()) {
				if (CALLBACKS.contains(method.getName()) && method.getParameterCount() == 0) {
					overrides++;
					if (!callsSuper(entity, method)) {
						violations.add(entity.getSimpleName() + "." + method.getName());
					}
				}
			}
		}
		assertThat(violations).as("overrides without super call (of %d overrides)", overrides).isEmpty();
	}

	@Test
	void theCheckDetectsAMissingSuperCall() throws Exception {
		assertThat(callsSuper(Good.class, Good.class.getDeclaredMethod("onCreate"))).isTrue();
		assertThat(callsSuper(Bad.class, Bad.class.getDeclaredMethod("onCreate"))).isFalse();
	}

	private static boolean callsSuper(Class<?> owner, Method method) throws IOException {
		String resource = owner.getName().replace('.', '/') + ".class";
		boolean[] found = { false };
		try (InputStream in = owner.getClassLoader().getResourceAsStream(resource)) {
			new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
						String[] exceptions) {
					if (!name.equals(method.getName()) || !descriptor.equals("()V")) {
						return null;
					}
					return new MethodVisitor(Opcodes.ASM9) {
						@Override
						public void visitMethodInsn(int opcode, String insnOwner, String insnName,
								String insnDescriptor, boolean isInterface) {
							if (opcode == Opcodes.INVOKESPECIAL && insnName.equals(name)
									&& insnDescriptor.equals("()V")) {
								found[0] = true;
							}
						}
					};
				}
			}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		}
		return found[0];
	}

	static class Good extends BaseEntity {

		@Override
		protected void onCreate() {
			super.onCreate();
		}

	}

	static class Bad extends BaseEntity {

		@Override
		protected void onCreate() {
		}

	}

}
