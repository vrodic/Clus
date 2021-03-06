package clus.ext.ensembles;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;

import clus.Clus;
import clus.data.rows.DataTuple;
import clus.data.rows.RowData;
import clus.data.type.ClusSchema;
import clus.error.ClusErrorList;
import clus.ext.hierarchical.ClassesTuple;
import clus.ext.hierarchical.ClassesValue;
import clus.ext.hierarchical.WHTDStatistic;
import clus.main.ClusOutput;
import clus.main.ClusRun;
import clus.main.ClusStatManager;
import clus.main.Settings;
import clus.model.ClusModel;
import clus.model.ClusModelInfo;
import clus.model.processor.ModelProcessorCollection;
import clus.selection.OOBSelection;
import clus.statistic.ClassificationStat;
import clus.statistic.ClusStatistic;
import clus.statistic.RegressionStat;
import clus.statistic.RegressionStatBase;
import clus.util.ClusException;

public class ClusOOBErrorEstimate {

	static HashMap m_OOBPredictions;
	static HashMap m_OOBUsage;
	static boolean m_OOBCalculation;
	int m_Mode;

	public ClusOOBErrorEstimate(int mode){
		m_OOBPredictions = new HashMap();
		m_OOBUsage = new HashMap();
		m_OOBCalculation = false;
		m_Mode = mode;
	}

	public static boolean containsPredictionForTuple(DataTuple tuple){
		return m_OOBPredictions.containsKey(tuple.hashCode());
	}

	public static double[] getPredictionForRegressionHMCTuple(DataTuple tuple){
		return (double[])m_OOBPredictions.get(tuple.hashCode());
	}

	public static double[][] getPredictionForClassificationTuple(DataTuple tuple){
		return (double[][])m_OOBPredictions.get(tuple.hashCode());
	}


	public void postProcessForestForOOBEstimate(ClusRun cr, OOBSelection oob_total, RowData all_data, Clus cl, String addname) throws ClusException, IOException{
		Settings sett = cr.getStatManager().getSettings();
		ClusSchema schema = all_data.getSchema();
		ClusOutput output = new ClusOutput(sett.getAppName() + addname +".oob", schema, sett);
		setOOBCalculation(true);

		//this is the part for writing the predictions from the OOB estimate
		//should new option in .s file be introduced???
//		ClusStatistic target = getStatManager().createStatistic(ClusAttrType.ATTR_USE_TARGET);
//		PredictionWriter wrt = new PredictionWriter(sett.getAppName() + addname	+ ".oob.pred", sett, target);
//		wrt.globalInitialize(schema);
//		ClusModelInfo allmi = cr.getAllModelsMI();
//		allmi.addModelProcessor(ClusModelInfo.TRAIN_ERR, wrt);
//		cr.copyAllModelsMIs();
//		wrt.initializeAll(schema);

		calcOOBError(oob_total,all_data, ClusModelInfo.TRAIN_ERR, cr);
		cl.calcExtraTrainingSetErrors(cr);
		output.writeHeader();
		output.writeOutput(cr, true, cl.getSettings().isOutTrainError());
		output.close();
//		wrt.close();
		setOOBCalculation(false);
//		m_OOBCalculation = false;
	}

	public synchronized void updateOOBTuples(OOBSelection oob_sel, RowData train_data, ClusModel model) throws IOException, ClusException{
		for (int i = 0; i < train_data.getNbRows(); i++){
			if (oob_sel.isSelected(i)){
				DataTuple tuple = train_data.getTuple(i);
				if (existsOOBtuple(tuple)) updateOOBTuple(tuple, model);
				else addOOBTuple(tuple, model);
			}
		}
	}

	public  boolean existsOOBtuple(DataTuple tuple){
		if (m_OOBUsage.containsKey(tuple.hashCode()) && m_OOBPredictions.containsKey(tuple.hashCode()))
			return true;
		if (!m_OOBUsage.containsKey(tuple.hashCode()) && m_OOBPredictions.containsKey(tuple.hashCode()))
			System.err.println(this.getClass().getName()+":existsOOBtuple(DataTuple) OOB tuples mismatch-> Usage = False, Predictions = True");
		if (m_OOBUsage.containsKey(tuple.hashCode()) && !m_OOBPredictions.containsKey(tuple.hashCode()))
			System.err.println(this.getClass().getName()+":existsOOBtuple(DataTuple) OOB tuples mismatch-> Usage = True, Predictions = False");
		return false;
	}

	public  void addOOBTuple(DataTuple tuple, ClusModel model){
		m_OOBUsage.put(tuple.hashCode(), 1);

		if (m_Mode == ClusStatManager.MODE_HIERARCHICAL){
			//for HMC we store the averages
			WHTDStatistic stat = (WHTDStatistic)model.predictWeighted(tuple);
			m_OOBPredictions.put(tuple.hashCode(),stat.getNumericPred());
		}

		if (m_Mode == ClusStatManager.MODE_REGRESSION){
			//for Regression we store the averages
			RegressionStat stat = (RegressionStat)model.predictWeighted(tuple);
			m_OOBPredictions.put(tuple.hashCode(),stat.getNumericPred());
		}

		if (m_Mode == ClusStatManager.MODE_CLASSIFY){
			//this should have a [][].for each attribute we store: Majority: the winning class, for Probability distribution, the class distribution
			ClassificationStat stat = (ClassificationStat)model.predictWeighted(tuple);
			switch (Settings.m_ClassificationVoteType.getValue()){//default is Majority Vote
				case 0: m_OOBPredictions.put(tuple.hashCode(), transformToMajority(stat.m_ClassCounts));break;
				case 1: m_OOBPredictions.put(tuple.hashCode(), transformToProbabilityDistribution(stat.m_ClassCounts));break;
				default: m_OOBPredictions.put(tuple.hashCode(), transformToMajority(stat.m_ClassCounts));
			}
		}
	}

	public synchronized  void updateOOBTuple(DataTuple tuple, ClusModel model){
		Integer used = (Integer)m_OOBUsage.get(tuple.hashCode());
		used = used.intValue()+1;
		m_OOBUsage.put(tuple.hashCode(), used);

		if (m_Mode == ClusStatManager.MODE_HIERARCHICAL){
			//the HMC and Regression have the same voting scheme: average
			WHTDStatistic stat = (WHTDStatistic)model.predictWeighted(tuple);
			double[] predictions = stat.getNumericPred();
			double[] avg_predictions = (double[])m_OOBPredictions.get(tuple.hashCode());
			avg_predictions = ClusEnsembleInduceOptimization.incrementPredictions(avg_predictions, predictions, used.doubleValue());
			m_OOBPredictions.put(tuple.hashCode(), avg_predictions);
		}

		if (m_Mode == ClusStatManager.MODE_REGRESSION){
			//the HMC and Regression have the same voting scheme: average
			RegressionStat stat = (RegressionStat)model.predictWeighted(tuple);
			double[] predictions = stat.getNumericPred();
			double[] avg_predictions = (double[])m_OOBPredictions.get(tuple.hashCode());
			avg_predictions = ClusEnsembleInduceOptimization.incrementPredictions(avg_predictions, predictions, used.doubleValue());
			m_OOBPredictions.put(tuple.hashCode(), avg_predictions);
		}

		if (m_Mode == ClusStatManager.MODE_CLASSIFY){
			//implement just addition!!!! and then
			ClassificationStat stat =(ClassificationStat) model.predictWeighted(tuple);
			double[][] predictions = stat.m_ClassCounts.clone();
			switch (Settings.m_ClassificationVoteType.getValue()){//default is Majority Vote
				case 0: predictions = transformToMajority(predictions);break;
				case 1: predictions = transformToProbabilityDistribution(predictions);break;
				default: predictions = transformToMajority(predictions);
			}
			double[][] sum_predictions = (double[][])m_OOBPredictions.get(tuple.hashCode());
			sum_predictions = ClusEnsembleInduceOptimization.incrementPredictions(sum_predictions, predictions);
			m_OOBPredictions.put(tuple.hashCode(), sum_predictions);
		}
	}

	public final void calcOOBError(OOBSelection oob_tot, RowData all_data, int type, ClusRun cr) throws IOException, ClusException {
		ClusSchema mschema = all_data.getSchema();
//		if (iter.shouldAttach()) attachModels(mschema, cr);
		cr.initModelProcessors(type, mschema);
		ModelProcessorCollection allcoll = cr.getAllModelsMI().getAddModelProcessors(type);
		DataTuple tuple;// = iter.readTuple();

                //D.Kocev - output predictions in human readable form
		//start
		FileWriter wrtr_oob = new FileWriter(new File(mschema.getSettings().getAppName()+"_oob.preds"));
		//end
                 //System.out.println("Size of predictions hashmap:" + ClusOOBErrorEstimate.m_OOBPredictions.size() + " all_data rows: " + all_data.getNbRows() + " cr models " + cr.getNbModels());
                 int selected = 0;
		for (int t = 0; t < all_data.getNbRows(); t++){
			if (oob_tot.isSelected(t)){
                   
				tuple = all_data.getTuple(t);
				allcoll.exampleUpdate(tuple);

                                //D.Kocev - output predictions in human readable form
				//start
				wrtr_oob.write("Instance ID = " + tuple.getDoubleVal(0) + "\n");
				//end
				for (int i = 0; i < cr.getNbModels(); i++) {
					ClusModelInfo mi = cr.getModelInfo(i);
					ClusModel model = mi.getModel();
					if (model != null) {
                                               selected++;                                                                                   
						ClusStatistic pred = model.predictWeighted(tuple);
                                                if (pred == null ) continue;
						ClusErrorList err = mi.getError(type);
						if (err != null) err.addExample(tuple, pred);
						ModelProcessorCollection coll = mi.getModelProcessors(type);
						if (coll != null) {
							if (coll.needsModelUpdate()) {
								model.applyModelProcessors(tuple, coll);
								coll.modelDone();
							}
							coll.exampleUpdate(tuple, pred);
						}
                                                //D.Kocev - output predictions in human readable form
						//start
						ClassesTuple pt = ((WHTDStatistic)pred).computePrintTuple();
						wrtr_oob.flush();
						wrtr_oob.write(mi.getName() + "\t");
						int count = 0;
						for (int clval = 0; clval < pt.getNbClasses(); clval++){
							ClassesValue cval = pt.getClass(clval);
							if (count != 0)wrtr_oob.write("@");
							wrtr_oob.write(cval.toPathString());
							count++;
						}
						if (count == 0)	wrtr_oob.write("none");
						else wrtr_oob.write("\t");
						for (int prob = 0; prob < pt.getNbClasses(); prob++){
							ClassesValue val = pt.getClass(prob);
							wrtr_oob.write(((RegressionStatBase)pred).getNumericPred()[val.getIndex()] + ";");
						}
						wrtr_oob.write("\n");
						//end
					}
				}
				allcoll.exampleDone();
			}
		}
               //  System.out.println("processed tuples: " + selected);
		cr.termModelProcessors(type);
            	//D.Kocev - output predictions in human readable form
		//start
		wrtr_oob.flush();
		wrtr_oob.close();
		System.out.println("Predictions written in " + mschema.getSettings().getAppName()+"_oob.preds");
		//end
	}


	public static boolean isOOBCalculation(){
		return m_OOBCalculation;
	}

	public void setOOBCalculation(boolean value){
		m_OOBCalculation = value;
	}

	//transform the class counts to majority vote (the one with max votes gets 1)
	public static double[][] transformToMajority(double[][] m_Counts){
		int[] maxPerTarget = new int[m_Counts.length];
		for (int i = 0; i < m_Counts.length; i++){
			maxPerTarget[i] = -1;
			double m_max = Double.NEGATIVE_INFINITY;
			for (int j = 0; j < m_Counts[i].length;j++){
				if (m_Counts[i][j]>m_max){
					maxPerTarget[i] = j;
					m_max = m_Counts[i][j];
				}
			}
		}
		double[][] result = new double[m_Counts.length][];
		for (int m = 0; m < m_Counts.length; m++){
			result[m] = new double[m_Counts[m].length];
			result[m][maxPerTarget[m]] ++; //the positions of max class will be 1
		}
		return result;
	}

	//transform the class counts to probability distributions
	public static double[][] transformToProbabilityDistribution(double[][] m_Counts){
		double[] sumPerTarget = new double[m_Counts.length];
		for (int i = 0; i < m_Counts.length; i++)
			for (int j = 0; j < m_Counts[i].length;j++)
				sumPerTarget[i] += m_Counts[i][j];
		double[][] result = new double[m_Counts.length][];

		for (int m = 0; m < m_Counts.length; m++){
			result[m] = new double[m_Counts[m].length];
			for (int n = 0; n < m_Counts[m].length; n++){
				result[m][n] = m_Counts[m][n]/sumPerTarget[m];
			}
		}
		return result;
	}

}
