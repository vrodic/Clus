
% HMC: Original: Average AUROC: 0.8833333333333334, Average AURPC: 0.9833333333333334, Average AURPC (weighted): 0.9880952380952381, Pooled AURPC: 0.9976190476190476
% HSC: 
% SC:  

[Data]
File = toyHMC.arff
PruneSet = toyHMC.arff
TestSet = toyHMC.arff

[Hierarchical]
Type = TREE
WType = ExpAvgParentWeight
HSeparator = /

[Tree]
FTest = [0.001,0.01,0.1,1.0]

[Model]
MinimalWeight = 1.0

