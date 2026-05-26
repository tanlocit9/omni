import debugpy

debugpy.listen(("0.0.0.0", 5678))
print("Waiting for debugger to attach on port 5678...")
debugpy.wait_for_client()

import uvicorn

uvicorn.run("main:app", reload=False)
