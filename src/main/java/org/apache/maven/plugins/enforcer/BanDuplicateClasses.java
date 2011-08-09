package org.apache.maven.plugins.enforcer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.enforcer.rule.api.EnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.artifact.InvalidDependencyVersionException;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.util.FileUtils;

/**
 * Bans duplicate classes on the classpath.
 */
public class BanDuplicateClasses extends AbstractStandardEnforcerRule {

	/**
	 * List of classes to ignore. Wildcard at the end accepted
	 */
	private String[] ignoreClasses;

	/**
	 * If {@code false} then the rule will fail at the first duplicate, if {@code true} then the rule will fail at the
	 * end.
	 */
	private boolean findAllDuplicates;

	/**
	 * If {@code false} then the rule will only inspect direct dependencies, if {@code true} then the rule will inspect
	 * transitive dependencies as well.
	 */
	private boolean findTransitiveDuplicates = true;

	/**
	 * Convert a wildcard into a regex.
	 * 
	 * @param wildcard
	 *            the wildcard to convert.
	 * @return the equivalent regex.
	 */
	private static String asRegex( final String wildcard ) {
		StringBuilder result = new StringBuilder( wildcard.length() );
		result.append( '^' );
		for( int index = 0; index < wildcard.length(); index++ ) {
			char character = wildcard.charAt( index );
			switch ( character ) {
				case '*':
					result.append( ".*" );
					break;
				case '?':
					result.append( "." );
					break;
				case '$':
				case '(':
				case ')':
				case '.':
				case '[':
				case '\\':
				case ']':
				case '^':
				case '{':
				case '|':
				case '}':
					result.append( "\\" );
				default:
					result.append( character );
					break;
			}
		}
		result.append( '$' );
		return result.toString();
	}

	/**
	 * {@inheritDoc}
	 */
	public void execute( final EnforcerRuleHelper helper ) throws EnforcerRuleException {
		Log log = helper.getLog();
		List< Pattern > ignores = new ArrayList< Pattern >();
		if ( ignoreClasses != null ) {
			for( String ignore : ignoreClasses ) {
				log.info( "Adding ignore: " + ignore );
				ignore = ignore.replace( '.', '/' );
				String pattern = asRegex( ignore );
				log.info( "Ignore: " + ignore + " maps to regex " + pattern );
				ignores.add( Pattern.compile( pattern ) );
			}
		}
		try {
			MavenProject project = (MavenProject)helper.evaluate( "${project}" );

			ArtifactFactory factory = (ArtifactFactory)helper.getComponent( ArtifactFactory.class );

			Set< Artifact > dependencyArtifacts = project.getDependencyArtifacts();

			if ( dependencyArtifacts == null ) {
				dependencyArtifacts = project.createArtifacts( factory, null, null );
			}

			if ( findTransitiveDuplicates ) {
				ArtifactResolver resolver = (ArtifactResolver)helper.getComponent( ArtifactResolver.class );
				ArtifactMetadataSource artifactMetadataSource = (ArtifactMetadataSource)helper.getComponent( ArtifactMetadataSource.class );
				ArtifactRepository localRepository = (ArtifactRepository)helper.evaluate( "${localRepository}" );
				List remoteRepositories = project.getRemoteArtifactRepositories();
				ArtifactResolutionResult result;
				try {
					result =
						resolver.resolveTransitively( dependencyArtifacts, project.getArtifact(), remoteRepositories, localRepository,
							artifactMetadataSource );
					Set< Artifact > transitiveArtifacts = result.getArtifacts();

					dependencyArtifacts.addAll( transitiveArtifacts );
				}
				catch ( ArtifactResolutionException exception ) {
					exception.printStackTrace();
				}
				catch ( ArtifactNotFoundException exception ) {
					exception.printStackTrace();
				}
			}

			Map< String, Artifact > classNames = new HashMap< String, Artifact >();
			Map< String, Set< Artifact >> duplicates = new HashMap< String, Set< Artifact >>();
			for( Artifact o : dependencyArtifacts ) {
				File file = o.getFile();
				log.debug( "Searching for duplicate classes in " + file );
				if ( !file.exists() ) {
					log.warn( "Could not find " + o + " at " + file );
				}
				else if ( file.isDirectory() ) {
					try {
						for( String name : (List< String >)FileUtils.getFileNames( file, null, null, false ) ) {
							log.info( "  " + name );
							checkAndAddName( o, name, classNames, duplicates, ignores, log );
						}
					}
					catch ( IOException e ) {
						throw new EnforcerRuleException( "Unable to process dependency " + o.toString() + " due to " + e.getLocalizedMessage(),
							e );
					}
				}
				else if ( file.isFile() ) {
					try {
						JarFile jar = new JarFile( file );
						try {
							for( JarEntry entry : Collections.< JarEntry > list( jar.entries() ) ) {
								checkAndAddName( o, entry.getName(), classNames, duplicates, ignores, log );
							}
						}
						finally {
							try {
								jar.close();
							}
							catch ( IOException e ) {
								// ignore
							}
						}
					}
					catch ( IOException e ) {
						throw new EnforcerRuleException( "Unable to process dependency " + o.toString() + " due to " + e.getLocalizedMessage(),
							e );
					}
				}
			}
			if ( !duplicates.isEmpty() ) {
				Map< Set< Artifact >, List< String >> inverted = new HashMap< Set< Artifact >, List< String >>();
				for( Map.Entry< String, Set< Artifact >> entry : duplicates.entrySet() ) {
					List< String > s = inverted.get( entry.getValue() );
					if ( s == null ) {
						s = new ArrayList< String >();
					}
					s.add( entry.getKey() );
					inverted.put( entry.getValue(), s );
				}
				StringBuilder buf = new StringBuilder( message == null
					? "Duplicate classes found:"
					: message );
				buf.append( '\n' );
				for( Map.Entry< Set< Artifact >, List< String >> entry : inverted.entrySet() ) {
					buf.append( "\n  Found in: " );
					for( Artifact a : entry.getKey() ) {
						buf.append( "\n    " );
						buf.append( a );
					}
					buf.append( "\n  Duplicate classes:" );
					for( String className : entry.getValue() ) {
						buf.append( "\n    " );
						buf.append( className );
					}
					buf.append( '\n' );
				}
				throw new EnforcerRuleException( buf.toString() );
			}

		}
		catch ( ComponentLookupException e ) {
			throw new EnforcerRuleException( "Unable to lookup a component " + e.getLocalizedMessage(), e );
		}
		catch ( ExpressionEvaluationException e ) {
			throw new EnforcerRuleException( "Unable to lookup an expression " + e.getLocalizedMessage(), e );
		}
		catch ( InvalidDependencyVersionException e ) {
			throw new EnforcerRuleException( "Unable to resolve dependencies" + e.getLocalizedMessage(), e );
		}
	}

	private void checkAndAddName( final Artifact artifact, final String name, final Map< String, Artifact > classNames,
		final Map< String, Set< Artifact >> duplicates, final Collection< Pattern > ignores, final Log log ) throws EnforcerRuleException {
		if ( !name.endsWith( ".class" ) ) {
			return;
		}
		Artifact dup = classNames.get( name );
		if ( dup != null ) {
			for( Pattern p : ignores ) {
				if ( p.matcher( name ).matches() ) {
					log.debug( "Ignoring duplicate class " + name );
					return;
				}
			}
			if ( findAllDuplicates ) {
				Set< Artifact > dups = duplicates.get( name );
				if ( dups == null ) {
					dups = new HashSet< Artifact >();
				}
				dups.add( artifact );
				dups.add( dup );
				duplicates.put( name, dups );
			}
			else {
				StringBuilder buf = new StringBuilder( message == null
					? "Duplicate class found:"
					: message );
				buf.append( '\n' );
				buf.append( "\n  Found in: " );
				buf.append( "\n    " );
				buf.append( dup );
				buf.append( "\n    " );
				buf.append( artifact );
				buf.append( "\n  Duplicate classes:" );
				buf.append( "\n    " );
				buf.append( name );
				buf.append( '\n' );
				buf.append( "There may be others but <findAllDuplicates> was set to false, so failing fast" );
				throw new EnforcerRuleException( buf.toString() );
			}
		}
		classNames.put( name, artifact );

	}

	/**
	 * {@inheritDoc}
	 */
	public boolean isCacheable() {
		return false;
	}

	/**
	 * {@inheritDoc}
	 */
	public boolean isResultValid( final EnforcerRule enforcerRule ) {
		return false;
	}

	/**
	 * {@inheritDoc}
	 */
	public String getCacheId() {
		return "Does not matter as not cacheable";
	}
}
