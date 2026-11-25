package skillexchange.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an API method that may be called without signing in.
 *
 * A custom annotation. It is kept at run time, so the server can read it by reflection: before it
 * runs a method for a client that has not signed in, it checks that the method carries this mark.
 * Every method without it needs a signed-in student.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Open {
}
