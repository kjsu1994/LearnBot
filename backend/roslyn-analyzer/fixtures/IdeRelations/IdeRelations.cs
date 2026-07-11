namespace IdeRelations;

public interface IJob
{
    void Run();
}

public sealed class Worker : IJob
{
    private int _count;

    public void Run()
    {
        Helper();
        _count = 1;
    }

    private int Helper()
    {
        return _count;
    }
}
