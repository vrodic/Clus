
[Data]
File = church_FUN.train.arff.zip
PruneSet = church_FUN.valid.arff.zip
TestSet = church_FUN.test.arff.zip

[Attributes]
ReduceMemoryNominalAttrs = yes

[Hierarchical]
Type = TREE
WType = ExpAvgParentWeight
HSeparator = /

[Tree]
ConvertToRules = No
FTest = [0.001,0.005,0.01,0.05,0.1,0.125]

[Model]
MinimalWeight = 5.0

[Output]
WritePredictions = {Test}

