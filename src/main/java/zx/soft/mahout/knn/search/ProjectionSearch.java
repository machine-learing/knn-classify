package zx.soft.mahout.knn.search;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

import org.apache.mahout.common.distance.DistanceMeasure;
import org.apache.mahout.math.DenseVector;
import org.apache.mahout.math.Vector;
import org.apache.mahout.math.function.DoubleFunction;
import org.apache.mahout.math.function.Functions;
import org.apache.mahout.math.random.WeightedThing;

import com.google.common.base.Preconditions;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * Does approximate nearest neighbor dudes search by projecting the data.
 */
public class ProjectionSearch extends UpdatableSearcher implements Iterable<Vector> {

	/**
	 * A lists of tree sets containing the scalar projections of each vector.
	 * The elements in a TreeSet are WeightedThing<Integer>, where the weight is the scalar
	 * projection of the vector at the index pointed to by the Integer from the referenceVectors list
	 * on the basis vector whose index is the same as the index of the TreeSet in the List.
	 */
	private List<TreeSet<WeightedThing<Vector>>> scalarProjections;

	/**
	 * The list of random normalized projection vectors forming a basis.
	 * The TreeSet of scalar projections at index i in scalarProjections corresponds to the vector
	 * at index i from basisVectors.
	 */
	private List<Vector> basisVectors;

	/**
	 * The number of elements to consider on both sides in the ball around the vector found by the
	 * search in a TreeSet from scalarProjections.
	 */
	private int searchSize;

	//	private int numDimensions;
	private final int numProjections;
	private boolean initialized = false;

	static List<Vector> generateBasis(int numDimensions, int numProjections) {
		final DoubleFunction random = Functions.random();
		List<Vector> basisVectors = Lists.newArrayList();
		for (int i = 0; i < numProjections; ++i) {
			Vector basisVector = new DenseVector(numDimensions);
			basisVector.assign(random);
			basisVector.normalize();
			basisVectors.add(basisVector);
		}
		return basisVectors;
	}

	private void initialize(int numDimensions) {
		if (initialized)
			return;
		initialized = true;
		basisVectors = generateBasis(numDimensions, numProjections);
		scalarProjections = Lists.newArrayList();
		for (int i = 0; i < numProjections; ++i) {
			scalarProjections.add(Sets.<WeightedThing<Vector>> newTreeSet());
		}
	}

	public ProjectionSearch(DistanceMeasure distanceMeasure, int numProjections, int searchSize) {
		super(distanceMeasure);
		Preconditions.checkArgument(numProjections > 0 && numProjections < 100,
				"Unreasonable value for number of projections");

		this.searchSize = searchSize;
		this.numProjections = numProjections;
	}

	/**
	 * Adds a WeightedVector into the set of projections for later searching.
	 * @param v  The WeightedVector to add.
	 */
	@Override
	public void add(Vector v) {
		initialize(v.size());
		Preconditions.checkArgument(v.size() == basisVectors.get(0).size(), "Invalid dimension of vector to add. "
				+ "Expected " + Integer.toString(basisVectors.get(0).size()) + " Got " + Integer.toString(v.size()));
		// Add the the new vector and the projected distance to each set separately.
		Iterator<Vector> basisVector = basisVectors.iterator();
		for (TreeSet<WeightedThing<Vector>> s : scalarProjections) {
			assert s.add(new WeightedThing<Vector>(v, v.dot(basisVector.next())));
		}
		int numVectors = scalarProjections.get(0).size();
		for (TreeSet<WeightedThing<Vector>> s : scalarProjections) {
			assert s.size() == numVectors;
			double firstWeight = s.first().getWeight();
			for (WeightedThing<Vector> w : s) {
				assert firstWeight <= w.getWeight();
				firstWeight = w.getWeight();
			}
		}
	}

	/**
	 * Returns the number of scalarProjections that we can search
	 * @return  The number of scalarProjections added to the search so far.
	 */
	@Override
	public int size() {
		if (scalarProjections == null)
			return 0;
		return scalarProjections.get(0).size();
	}

	/**
	 * Searches for the query vector returning the closest limit referenceVectors.
	 *
	 * @param query the vector to search for.
	 * @param limit the number of results to return.
	 * @return
	 */
	@Override
	public List<WeightedThing<Vector>> search(final Vector query, int limit) {
		HashSet<Vector> candidates = Sets.newHashSet();

		Iterator<Vector> projections = basisVectors.iterator();
		for (TreeSet<WeightedThing<Vector>> v : scalarProjections) {
			Vector basisVector = projections.next();
			WeightedThing<Vector> projectedQuery = new WeightedThing<Vector>(query, query.dot(basisVector));
			for (WeightedThing<Vector> candidate : Iterables.concat(
					Iterables.limit(v.tailSet(projectedQuery, true), searchSize),
					Iterables.limit(v.headSet(projectedQuery, false).descendingSet(), searchSize))) {
				candidates.add(candidate.getValue());
			}
		}

		// If searchSize * scalarProjections.size() is small enough not to cause much memory pressure,
		// this is probably just as fast as a priority queue here.
		List<WeightedThing<Vector>> top = Lists.newArrayList();
		for (Vector candidate : candidates) {
			top.add(new WeightedThing<Vector>(candidate, distanceMeasure.distance(query, candidate)));
		}
		Collections.sort(top);
		return top.subList(0, Math.min(limit, top.size()));
	}

	public int getSearchSize() {
		return searchSize;
	}

	public void setSearchSize(int searchSize) {
		this.searchSize = searchSize;
	}

	@Override
	public Iterator<Vector> iterator() {
		return new AbstractIterator<Vector>() {
			private final Iterator<WeightedThing<Vector>> projected = scalarProjections.get(0).iterator();

			@Override
			protected Vector computeNext() {
				if (!projected.hasNext()) {
					return endOfData();
				}
				return projected.next().getValue();
			}
		};
	}

	@Override
	public boolean remove(Vector vector, double epsilon) {
		List<WeightedThing<Vector>> x = search(vector, 1);
		if (x.get(0).getWeight() < 1e-7) {
			Iterator<Vector> basisVectors = this.basisVectors.iterator();
			for (TreeSet<WeightedThing<Vector>> projection : scalarProjections) {
				if (!projection.remove(new WeightedThing<Integer>(-1, vector.dot(basisVectors.next())))) {
					throw new RuntimeException("Internal inconsistency in ProjectionSearch");
				}
			}
			return true;
		} else {
			return false;
		}
	}

	@Override
	public void clear() {
		for (TreeSet<WeightedThing<Vector>> set : scalarProjections) {
			set.clear();
		}
	}

}
