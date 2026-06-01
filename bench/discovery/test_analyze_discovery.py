import csv, io, analyze_discovery as ad

CSV = """arm,instance_id,answered,file_f1,symbol_f1,judge_score,in_tokens,out_tokens,cache_read,cache_create,turns,wall_s,cost_usd,denied_attempts,is_error
semantic,a,1,1.0,1.0,1.0,10,20,0,0,5,30.0,0.10,2,false
semantic,b,1,0.5,0.5,0.6,10,20,0,0,7,40.0,0.20,1,false
baseline,a,1,0.5,0.0,0.4,10,20,0,0,9,60.0,0.30,0,false
baseline,b,0,0.0,0.0,0.0,10,20,0,0,3,20.0,0.05,0,false
"""

def test_load_and_rates():
    rows = ad.load(io.StringIO(CSV))
    sem = [r for r in rows if r["arm"] == "semantic"]
    base = [r for r in rows if r["arm"] == "baseline"]
    assert ad.answer_rate(sem) == 1.0
    assert ad.answer_rate(base) == 0.5

def test_median_file_f1():
    rows = ad.load(io.StringIO(CSV))
    sem = [r for r in rows if r["arm"] == "semantic"]
    assert ad.med([r["file_f1"] for r in sem]) == 0.75
