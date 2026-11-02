// Reflection harness for the official assemblies (SeatsMobile.dll and
// Seats.Gateway.Mobile.Client.dll). Those files are NOT part of this repository.
// Usage:
//   set SEATS_LIBS=D:\path\to\extracted\libs
//   dotnet run
using System.Reflection;
using System.Runtime.Loader;

var libraryDirectory = Environment.GetEnvironmentVariable("SEATS_LIBS");
if (string.IsNullOrWhiteSpace(libraryDirectory) || !Directory.Exists(libraryDirectory))
{
	Console.WriteLine("Set SEATS_LIBS to the folder containing SeatsMobile.dll and Seats.Gateway.Mobile.Client.dll");
	return 1;
}

AssemblyLoadContext.Default.Resolving += (context, name) =>
{
	var assemblyPath = Path.Combine(libraryDirectory, name.Name + ".dll");
	return File.Exists(assemblyPath) ? context.LoadFromAssemblyPath(assemblyPath) : null;
};

var seats = AssemblyLoadContext.Default.LoadFromAssemblyPath(Path.Combine(libraryDirectory, "SeatsMobile.dll"));
var murmurType = seats.GetType("SeatsMobile.BusinessLayer.Implementations.MurmurHash2");
var dammType = seats.GetType("SeatsMobile.BusinessLayer.Implementations.DammAlgorithm");
var qrType = seats.GetType("SeatsMobile.BusinessLayer.Implementations.AutoRefreshingQr");
if (murmurType != null && dammType != null && qrType != null)
{
	var murmur = Activator.CreateInstance(murmurType);
	var damm = Activator.CreateInstance(dammType);
	var refreshingQr = qrType.GetConstructors().First().Invoke(new object?[] { null, damm, murmur, null });
	var getTotpMethod = qrType.GetMethod("GetTOTP")!;
	foreach (var counter in new long[] { 0, 1, 12345, 999999 })
		Console.WriteLine($"csharp totp {counter}: {getTotpMethod.Invoke(refreshingQr, new object[] { "832105", counter })}");
}

var gateway = AssemblyLoadContext.Default.LoadFromAssemblyPath(Path.Combine(libraryDirectory, "Seats.Gateway.Mobile.Client.dll"));
var keyType = gateway.GetTypes().FirstOrDefault(type => type.FullName == "A.b");
Console.WriteLine("key type: " + keyType);
if (keyType != null)
{
	var keyMethod = keyType.GetMethods(BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic)
		.FirstOrDefault(method => method.Name == "a" && method.GetParameters().Length == 1 && method.GetParameters()[0].ParameterType == typeof(string));
	if (keyMethod != null)
	{
		foreach (var input in new[] { "", "0011223344556677", "31323334353637383930" })
		{
			var result = keyMethod.Invoke(null, new object[] { input });
			Console.WriteLine($"key('{input}'): {(result is byte[] bytes ? Convert.ToHexString(bytes) : result)}");
		}
	}
}
return 0;