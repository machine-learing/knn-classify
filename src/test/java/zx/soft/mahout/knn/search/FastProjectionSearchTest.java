package zx.soft.mahout.knn.search;

import java.util.List;

import org.apache.mahout.common.distance.DistanceMeasure;
import org.apache.mahout.common.distance.EuclideanDistanceMeasure;
import org.apache.mahout.math.DenseMatrix;
import org.apache.mahout.math.Matrix;
import org.apache.mahout.math.MatrixSlice;
import org.apache.mahout.math.Vector;
import org.apache.mahout.math.random.WeightedThing;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import zx.soft.mahout.knn.LumpyData;
import zx.soft.mahout.knn.search.BruteSearch;
import zx.soft.mahout.knn.search.FastProjectionSearch;
import zx.soft.mahout.knn.search.UpdatableSearcher;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

@SuppressWarnings("unused")
public class FastProjectionSearchTest extends AbstractSearchTest {

	private static Matrix data;
	private static final int QUERIES = 20;
	private static final int SEARCH_SIZE = 300;
	private static final int MAX_DEPTH = 100;

	@BeforeClass
	public static void setUp() {
		data = randomData();
	}

	@Override
	public Iterable<MatrixSlice> testData() {
		return data;
	}

	static class StripWeight implements Function<WeightedThing<Vector>, Vector> {
		@Override
		public Vector apply(WeightedThing<Vector> input) {
			Preconditions.checkArgument(input != null);
			return input.getValue();
		}
	};

	@Test
	public void testEpsilon() {
		final int dataSize = 10000;
		final int querySize = 30;
		final DistanceMeasure metric = new EuclideanDistanceMeasure();

		// these determine the dimension for the test. Each scale is multiplied by each multiplier
		final List<Integer> scales = ImmutableList.of(10);
		final List<Integer> multipliers = ImmutableList.of(1, 2, 3, 5);

		for (Integer scale : scales) {
			for (Integer multiplier : multipliers) {
				int d = scale * multiplier;
				if (d == 1) {
					continue;
				}
				final Matrix data = new DenseMatrix(dataSize + querySize, d);
				final LumpyData clusters = new LumpyData(d, 0.05, 10);
				for (MatrixSlice row : data) {
					row.vector().assign(clusters.sample());
				}

				Matrix q = data.viewPart(0, querySize, 0, d);
				Matrix m = data.viewPart(querySize, dataSize, 0, d);

				BruteSearch brute = new BruteSearch(metric);
				brute.addAllMatrixSlices(m);
				FastProjectionSearch test = new FastProjectionSearch(metric, d, 20);
				test.addAllMatrixSlices(m);

				int bigRatio = 0;
				double averageOverlap = 0;
				for (MatrixSlice qx : q) {
					final Vector query = qx.vector();
					final List<WeightedThing<Vector>> r1 = brute.search(query, 20);
					Vector v1 = r1.get(0).getValue();
					final List<WeightedThing<Vector>> r2 = test.search(query, 30);
					Vector v2 = r2.get(0).getValue();

					for (Vector v : Iterables.transform(r1, new StripWeight())) {
						for (Vector w : Iterables.transform(r2, new StripWeight())) {
							if (v.equals(w))
								++averageOverlap;
						}
					}
					if (r2.get(0).getWeight() / r1.get(0).getWeight() > 1.4) {
						System.out.printf("[fast-projection] %f [brute] %f [ratio] %f\n", r2.get(0).getWeight(), r1
								.get(0).getWeight(), r2.get(0).getWeight() / r1.get(0).getWeight());
						bigRatio++;
					}
				}
				averageOverlap = averageOverlap / q.rowSize();

				// Assert.assertTrue(bigRatio < 2);
				Assert.assertTrue(averageOverlap > 7);
			}
		}
	}

	@Override
	public UpdatableSearcher getSearch(int n) {
		return new FastProjectionSearch(new EuclideanDistanceMeasure(), 4, 20);
	}
}
