package clus.heuristic;

import clus.data.io.ClusReader;
import clus.data.rows.RowData;
import clus.data.rows.DataTuple;
import clus.ext.sspd.SSPDMatrix;
import clus.main.Settings;
import clus.statistic.GeneticDistanceStat;
import clus.statistic.ClusStatistic;
import jeans.resource.ResourceInfo;
import jeans.list.BitList;

import java.io.IOException;
import java.util.*;

import jeans.math.matrix.*;

public class GeneticDistanceHeuristicMatrix extends GeneticDistanceHeuristic {

	protected MSymMatrix m_DistMatrix; // the distance matrix (read or computed)
	protected double m_SumAllDistances; // the sum of all pairwise distances in the data in the current node
	protected HashMap m_HeurComputed = new HashMap(); // keep track of what has been computed before
	protected double[] m_SumDistWithCompl; // for each sequence in the current node store the sum of the pairwise distances with the complement data
	protected int m_SampleSize=20; // sample size
	protected boolean m_Sampling=false; // whether sampling is switched on

	public long m_SetDataTimer=0;
	public long m_HeurTimer=0;

	protected double m_OerSumAllDistances; // sum of pairwise distances in the complete dataset

	// executed once, when splitting the root node
	public void setInitialData(ClusStatistic stat, RowData data) {
		m_OerData = data;
		m_OerData.addIndices();
		constructMatrix(stat);
	}

	// try to read distance matrix; if not present, compute it
	public void constructMatrix(ClusStatistic stat) {
		try {
			m_DistMatrix = read(m_OerData.getSchema().getSettings());
		}
		catch (IOException e) {
			m_DistMatrix = new MSymMatrix(m_OerData.getNbRows());
			System.out.println("  Calculating Distance Matrix (Size: "+m_OerData.getNbRows()+")");
			GeneticDistanceStat gstat = (GeneticDistanceStat)stat;
			for (int i=0; i<m_OerData.getNbRows(); i++) {
				DataTuple tuple1 = m_OerData.getTuple(i);
				int row = tuple1.getIndex();
				String[] str1 = new String[gstat.m_NbTarget];
				for (int t=0; t<gstat.m_NbTarget; t++) {
					int nomvalue1 = gstat.m_Attrs[t].getNominal(tuple1);
					str1[t] = gstat.m_Attrs[t].getValueOrMissing(nomvalue1);
				}

				for (int j=i+1; j<m_OerData.getNbRows(); j++) {
					DataTuple tuple2 = m_OerData.getTuple(j);
					int col = tuple2.getIndex();
					String[] str2 = new String[gstat.m_NbTarget];
					for (int t=0; t<gstat.m_NbTarget; t++) {
						int nomvalue2 = gstat.m_Attrs[t].getNominal(tuple2);
						str2[t] = gstat.m_Attrs[t].getValueOrMissing(nomvalue2);
					}
					double distance = getDistance(str1,str2);
					m_DistMatrix.set_sym(row, col, distance);
				}
			}
		}
		/*System.out.println("Distance matrix: ");
		for (int i=0; i<m_OerData.getNbRows(); i++) {
			for (int j=0; j<=i; j++) {
				System.out.print(m_DistMatrix.get(i, j) + "  ");
			}
			System.out.println();
		}*/
	}

	// reading distance matrix (default name is "dist")
	public MSymMatrix read(Settings sett) throws IOException {
		String filename = sett.getPhylogenyDistanceMatrix();
		ClusReader reader = new ClusReader(filename, sett);
		int nb = (int) reader.readFloat();
		System.out.println("  Loading Distance Matrix: "+filename+" (Size: "+nb+")");
		MSymMatrix matrix = new MSymMatrix(nb);
		for (int i = 0; i < nb; i++) {
			reader.readName();
			for (int j = 0; j < nb; j++) {
				double value = reader.readFloat();
				if (i<=j) matrix.set_sym(i, j, value);
			}
			reader.readTillEol();
			//if (!reader.isEol()) throw new IOException("Distance Matrix is not square");
		}
		reader.close();
		System.out.println("  Matrix loaded");
		return matrix;
	}

	// executed each time a node has to be split
	public void setData(RowData data) {
		m_Data = data;
		if (data.getNbRows() > 2) {

		//long start_time = System.currentTimeMillis();

		m_HeurComputed.clear();
		m_DataIndices = constructIndexVector(m_Data);
		m_SumAllDistances = getSumOfDistancesWithin(m_DataIndices);
		m_ComplDataIndices = constructComplIndexVector(m_OerData, m_DataIndices);
		m_SumDistWithCompl = constructComplDistVector(m_DataIndices, m_ComplDataIndices);

		//long stop_time = System.currentTimeMillis();
		//long elapsed = stop_time - start_time;
		//m_SetDataTimer += elapsed;
		}

		if (m_Data.getNbRows() == m_OerData.getNbRows()) {
			m_OerSumAllDistances = m_SumAllDistances;
		}
	}

	// for each sequence, compute the sum of its pairwise distances to the complement sequences
	public double[] constructComplDistVector(int[] indices, int[] complIndices) {
		int nbindices = indices.length;
		int nbcomplindices = complIndices.length;
		double[] resultvector = new double[nbindices];
		double sumdist;
		for (int i=0; i<nbindices; i++) {
			sumdist=0.0;
			int matrixrow = indices[i];
			for (int j=0; j<nbcomplindices; j++) {
				int matrixcol = complIndices[j];
				sumdist += m_DistMatrix.get(matrixrow,matrixcol);
			}
			resultvector[i] = sumdist;
		}
		return resultvector;
	}

	// calculate the sum of the pairwise distances between two sets
	public double getSumPairwiseDistanceBetween(int[] posindices, int[] negindices) {
		double dist = 0.0;
		for (int i=0; i<posindices.length; i++) {
			int row = posindices[i];
			for (int j=0; j<negindices.length; j++) {
				int col = negindices[j];
				dist += m_DistMatrix.get(row,col);
			}
		}
		return dist;
	}

	// calculate the average of the pairwise distances between two sets
	public double getAvgPairwiseDistanceBetween(int[] posindices, int[] negindices) {
		double dist = getSumPairwiseDistanceBetween(posindices, negindices);
		double nbpairs = posindices.length * negindices.length;
		return (dist / nbpairs);
	}

	// calculate the sum of the pairwise distances within a set
	public double getSumOfDistancesWithin(int[] indices) {
		int nb_ex = indices.length;
		int nb_pairs = (nb_ex * (nb_ex-1))/2;
		double sum = 0.0;
		for (int i=0; i<nb_ex; i++) {
			int row = indices[i];
			for (int j=i+1; j<nb_ex; j++) {
				int col = indices[j];
				sum += m_DistMatrix.get(row,col);
			}
		}
		return sum;
	}


	public double calcHeuristic(ClusStatistic c_tstat, ClusStatistic c_pstat, ClusStatistic missing) {
		switch (Settings.m_PhylogenyCriterion.getValue()) {
		case Settings.PHYLOGENY_CRITERION_BRANCHLENGTHS:
			return calcHeuristicBranchLengths(c_tstat, c_pstat, null);
		case Settings.PHYLOGENY_CRITERION_MAXAVGPWDIST:
			return calcHeuristicArslan(c_tstat, c_pstat, null);
		}
		return 0.0; // never executed
	}

	public double calcHeuristic(ClusStatistic c_tstat, ClusStatistic[] array_stat, int nbsplit) {
		ClusStatistic p_stat = array_stat[0];
		ClusStatistic part_stat = array_stat[1];
		switch (Settings.m_PhylogenyCriterion.getValue()) {
		case Settings.PHYLOGENY_CRITERION_BRANCHLENGTHS:
			return calcHeuristicBranchLengths(c_tstat, p_stat, part_stat);
		case Settings.PHYLOGENY_CRITERION_MAXAVGPWDIST:
			return calcHeuristicArslan(c_tstat, p_stat, null);
		}
		return 0.0; // never executed
	}

	/*
	 * Heuristic that calculates the avg pairwise distance between elements from the positive and negative set.
	 * This is the heuristic that is also used by Arslan and Bizargity.
	 * The test that yields the largest heuristic will be chosen in the end (maximal avg pairwise distance).
	 */
	public double calcHeuristicArslan(ClusStatistic c_tstat, ClusStatistic c_pstat, ClusStatistic missing) {

		// first create all needed statistics and data
		GeneticDistanceStat tstat = (GeneticDistanceStat)c_tstat;
		GeneticDistanceStat pstat = (GeneticDistanceStat)c_pstat;
		GeneticDistanceStat nstat = (GeneticDistanceStat)tstat.cloneStat();
		nstat.copy(tstat);
		nstat.subtractFromThis(pstat);

		double n_pos = pstat.m_SumWeight;
		double n_neg = nstat.m_SumWeight;

		// Acceptable test?
		if (n_pos < Settings.MINIMAL_WEIGHT || n_neg < Settings.MINIMAL_WEIGHT) {
			return Double.NEGATIVE_INFINITY;
		}

		// If position missing for some sequence, don't use it in split (probably this approach is not optimal)
		if (Math.round(n_pos) != n_pos || Math.round(n_neg) != n_neg) {
			return Double.NEGATIVE_INFINITY;
		}
		//-----------

		int[] posindices = constructIndexVector(m_Data, pstat);
		int[] negindices = constructIndexVector(m_Data, nstat);


		double result = getAvgPairwiseDistanceBetween(posindices, negindices);
		return result;
	}


	/*
	 * Efficiency improvement:
	 * Here we calculate the sum of distances within posindices and negindices in the context of a 2-nucleotide
	 * test: e.g. p14 in {A,C}.
	 * We make use of the previous results for p14=A and p14=C, which have been calculated and hashed before.
	 */
	public double[] effcalculate(GeneticDistanceStat pstat, GeneticDistanceStat partition, int[] negindices) {
		//System.out.println("efficient");
		String part1bits = partition.getBits().toString();

		double part1poswithin = -100000.0;
		double part1negwithin = -100000.0;

		ArrayList ResAl = (ArrayList) m_HeurComputed.get(part1bits);
		if (ResAl!=null) {
			part1poswithin = ((Double)ResAl.get(1)).doubleValue();
			part1negwithin = ((Double)ResAl.get(2)).doubleValue();
		}
		else {
			System.out.println("------- Partition not found ------");
		}

		int[] part1posindices = constructIndexVector(m_Data, partition);

		GeneticDistanceStat p2stat = (GeneticDistanceStat)pstat.cloneStat();
		p2stat.copy(pstat);
		p2stat.subtractFromThis(partition);

		BitList part2bitl = p2stat.getBits();

		double part2poswithin = -100000.0;
		ArrayList ResAl2 = (ArrayList) m_HeurComputed.get(part2bitl.toString());
		if (ResAl2!=null) {
			part2poswithin = ((Double)ResAl2.get(1)).doubleValue();
		}
		else {
			System.out.println("------- Partition2 not found ------");
		}

		int[] part2posindices = constructIndexVector(m_Data, p2stat);

		double poswithin = part1poswithin + part2poswithin + getSumPairwiseDistanceBetween(part1posindices, part2posindices);
		double negwithin = part1negwithin - part2poswithin - getSumPairwiseDistanceBetween(part2posindices, negindices);

		double[] result = new double[2];
		result[0] = poswithin;
		result[1] = negwithin;

		return result;
	}

	/*
	 * Heuristic that calculates the total branch lengths.
	 * It uses the distance matrix, and stores previously computed results.
	 * The test that yields the largest heuristic will be chosen in the end. Since we want to minimize the total branch length, we maximize the inverse of it.
	 */
	public double calcHeuristicBranchLengths(ClusStatistic c_tstat, ClusStatistic c_pstat, ClusStatistic partition) {
		// first create all needed statistics and data
		GeneticDistanceStat tstat = (GeneticDistanceStat)c_tstat;
		GeneticDistanceStat pstat = (GeneticDistanceStat)c_pstat;

		double n_pos = pstat.m_SumWeight;
		double n_neg = tstat.m_SumWeight - pstat.m_SumWeight;

		// Acceptable test?
		if (n_pos < Settings.MINIMAL_WEIGHT || n_neg < Settings.MINIMAL_WEIGHT) {
			return Double.NEGATIVE_INFINITY;
		}

		// If position missing for some sequence, don't use it in split (probably this approach is not optimal)
		// By default, these positions can be used in split, but examples with missing values do not play a role in calculating heuristic
		if (n_pos + n_neg != m_Data.getNbRows()) {
			return Double.NEGATIVE_INFINITY;
		}

		// If only 2 sequences left and one is pos and one is neg (the latter is automatically true, since the last test passed), we don't need to calculate anything
		if ((n_pos+n_neg) == 2*Settings.MINIMAL_WEIGHT) {
			return Double.POSITIVE_INFINITY;
		}

		//-----------

		// we check whether this split has been computed before
		String key = pstat.getBits().toString();
		ArrayList ResAl = (ArrayList) m_HeurComputed.get(key);
		if (ResAl!=null) {
			Double value = (Double) ResAl.get(0);
			return value.doubleValue();
		}

		GeneticDistanceStat nstat = (GeneticDistanceStat)tstat.cloneStat();
		nstat.copy(tstat);
		nstat.subtractFromThis(pstat);

		// we also check whether the complement split has been computed before (left subtree <-> right subtree)
		key = pstat.getBits().toString();
		ResAl = (ArrayList) m_HeurComputed.get(key);
		if (ResAl!=null) {
			Double value = (Double) ResAl.get(0);
			return value.doubleValue();
		}

		int[] posindices = constructIndexVector(m_Data, pstat);
		int[] negindices = constructIndexVector(m_Data, nstat);

		double poswithin = 0.0;
		double negwithin = 0.0;

		if (partition != null) { // efficiency trick
			GeneticDistanceStat part = (GeneticDistanceStat)partition;
			double[] withins = effcalculate(pstat, part, negindices);
			poswithin = withins[0];
			negwithin = withins[1];
		}
		else {
			poswithin = getSumOfDistancesWithin(posindices);
			negwithin = getSumOfDistancesWithin(negindices);
		}


		double result;
		// root of the tree
		if (m_Data.getNbRows() == m_OerData.getNbRows()) {
			result = (m_SumAllDistances + (n_neg-1) * poswithin + (n_pos-1) * negwithin) / (n_pos*n_neg);
		}

		// other nodes
		else {
			double sumDistPosToCompl = 0.0;
			double sumDistNegToCompl = 0.0;
			for (int i=0; i<tstat.m_SumWeight; i++) {
				if (pstat.getBits().getBit(i)) {
					sumDistPosToCompl += m_SumDistWithCompl[i];
				}
				else {
					sumDistNegToCompl += m_SumDistWithCompl[i];
				}
			}
			double n_compl = m_ComplDataIndices.length;

			// for the exact total branch length:
			// double compdist = getSumOfDistancesWithin(m_ComplDataIndices); // if you want to compute exact total branch lengths, add this to result
			// result = ((sumDistNegToCompl / (n_neg*n_compl)) + (sumDistPosToCompl / (n_pos*n_compl)) + (m_SumAllDistances / (n_pos*n_neg)) + (getSumOfDistancesWithin(posindices) * (2*n_neg - 1) / (n_pos*n_neg)) + (getSumOfDistancesWithin(negindices) * (2*n_pos - 1) / (n_pos*n_neg)))/2 + (compdist / n_compl);

			// otherwise (finds same splits, but less computations):
			result = (sumDistNegToCompl / (n_neg*n_compl)) + (sumDistPosToCompl / (n_pos*n_compl)) + (m_SumAllDistances / (n_pos*n_neg)) + (poswithin * (2*n_neg - 1) / (n_pos*n_neg)) + (negwithin * (2*n_pos - 1) / (n_pos*n_neg));
		}

		double finalresult = -1.0 * result; // we want to minimize total branch length, but in Clus, the heuristic is maximized

		// we store the result
		// the poswithin and negwithin are stored for the effcalculate method
		key = pstat.getBits().toString();
		ArrayList<Double> al = new ArrayList<Double>(3);
		al.add(new Double(finalresult));
		al.add(new Double(poswithin));
		al.add(new Double(negwithin));
		m_HeurComputed.put(key,al);
		// we also store the complement split (left subtree <-> right subtree)
		key = nstat.getBits().toString();
		ArrayList<Double> al2 = new ArrayList<Double>(3);
		al2.add(new Double(finalresult));
		al2.add(new Double(negwithin));
		al2.add(new Double(poswithin));
		m_HeurComputed.put(key,al2);

		return finalresult;
	}


	public String getName() {
		switch (Settings.m_PhylogenyCriterion.getValue()) {
		case Settings.PHYLOGENY_CRITERION_BRANCHLENGTHS:
			return "GeneticDistanceHeuristicMatrix -> Minimize total branch length";
		case Settings.PHYLOGENY_CRITERION_MAXAVGPWDIST:
			return "GeneticDistanceHeuristicMatrix -> Maximize avg pairwise distance";
		}
		return "GeneticDistanceHeuristicMatrix";
	}

}
